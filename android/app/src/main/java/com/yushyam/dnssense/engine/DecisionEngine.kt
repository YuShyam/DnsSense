package com.yushyam.dnssense.engine

import com.yushyam.dnssense.data.model.DecisionOutcome
import com.yushyam.dnssense.data.model.DecisionReason
import com.yushyam.dnssense.data.model.DnsSenseConfig
import com.yushyam.dnssense.data.model.HostnameValidator
import com.yushyam.dnssense.data.model.NetworkSnapshot
import com.yushyam.dnssense.data.model.NetworkTransport
import com.yushyam.dnssense.data.model.PrivateDnsMode
import com.yushyam.dnssense.data.model.ProbeResult

/**
 * 雙層決策引擎 (Two-Layer Pure Decision Engine)
 */
object DecisionEngine {

    fun evaluate(
        config: DnsSenseConfig,
        snapshot: NetworkSnapshot,
        tempBypassNetworkKey: String?,
        hasSecurePermission: Boolean,
        probeResult: ProbeResult?
    ): DecisionOutcome {
        val advisoryOnly = !hasSecurePermission

        if (!config.enabled) {
            return DecisionOutcome(
                targetMode = null,
                reason = DecisionReason.GUARD_DISABLED,
                detailZh = "自動切換已暫停，維持系統現狀",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (snapshot.transport == NetworkTransport.NONE) {
            return DecisionOutcome(
                targetMode = null,
                reason = DecisionReason.NO_NETWORK,
                detailZh = "目前無網路連線，暫不調整 DNS",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (!HostnameValidator.isValid(config.hostname)) {
            return DecisionOutcome(
                targetMode = null,
                reason = DecisionReason.INVALID_HOSTNAME,
                detailZh = "私人 DNS 位址格式無效：${config.hostname}",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (!tempBypassNetworkKey.isNullOrBlank() && tempBypassNetworkKey == snapshot.networkKey) {
            return DecisionOutcome(
                targetMode = PrivateDnsMode.OPPORTUNISTIC,
                reason = DecisionReason.TEMP_MANUAL_BYPASS,
                detailZh = "此連線暫時使用自動 DNS，中斷連線後將恢復私人 DNS",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (snapshot.transport == NetworkTransport.CELLULAR && config.bypassCellular) {
            return DecisionOutcome(
                targetMode = PrivateDnsMode.OPPORTUNISTIC,
                reason = DecisionReason.BYPASS_CELLULAR,
                detailZh = "已設定排除行動網路 (4G/5G)，切換為自動 DNS",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (snapshot.transport == NetworkTransport.WIFI) {
            val currentSsid = NetworkSnapshot.normalizeSsid(snapshot.ssid)
            val matched = currentSsid.isNotEmpty() && config.bypassSsids.any {
                NetworkSnapshot.normalizeSsid(it).equals(currentSsid, ignoreCase = true)
            }
            if (matched) {
                return DecisionOutcome(
                    targetMode = PrivateDnsMode.OPPORTUNISTIC,
                    reason = DecisionReason.BYPASS_WIFI_SSID,
                    detailZh = "Wi-Fi「$currentSsid」位於排除清單，切換為自動 DNS",
                    isAdvisoryOnly = advisoryOnly
                )
            }
        }

        if (snapshot.isCaptivePortal) {
            return DecisionOutcome(
                targetMode = PrivateDnsMode.OPPORTUNISTIC,
                reason = DecisionReason.CAPTIVE_PORTAL_DETECTED,
                detailZh = "網路需要網頁登入認證，暫時切換為自動 DNS",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (config.autoProbeEnabled && probeResult != null) {
            return if (probeResult.isHealthy) {
                DecisionOutcome(
                    targetMode = PrivateDnsMode.HOSTNAME,
                    reason = DecisionReason.DOT_PROBE_HEALTHY,
                    detailZh = "${probeResult.reason}，啟用私人 DNS (${config.hostname})",
                    isAdvisoryOnly = advisoryOnly
                )
            } else {
                DecisionOutcome(
                    targetMode = PrivateDnsMode.OPPORTUNISTIC,
                    reason = DecisionReason.DOT_PROBE_FAILED,
                    detailZh = "${probeResult.reason}，切換為自動 DNS",
                    isAdvisoryOnly = advisoryOnly
                )
            }
        }

        return DecisionOutcome(
            targetMode = PrivateDnsMode.HOSTNAME,
            reason = DecisionReason.DEFAULT_PRIVATE_DNS,
            detailZh = "非排除網路，啟用私人 DNS (${config.hostname})",
            isAdvisoryOnly = advisoryOnly
        )
    }

    fun shouldRunDotProbe(
        config: DnsSenseConfig,
        snapshot: NetworkSnapshot,
        tempBypassNetworkKey: String?
    ): Boolean {
        if (!config.enabled || !config.autoProbeEnabled) return false
        if (snapshot.transport == NetworkTransport.NONE) return false
        if (!HostnameValidator.isValid(config.hostname)) return false
        if (!tempBypassNetworkKey.isNullOrBlank() && tempBypassNetworkKey == snapshot.networkKey) return false
        if (snapshot.transport == NetworkTransport.CELLULAR && config.bypassCellular) return false
        if (snapshot.transport == NetworkTransport.WIFI) {
            val currentSsid = NetworkSnapshot.normalizeSsid(snapshot.ssid)
            if (currentSsid.isNotEmpty() && config.bypassSsids.any {
                    NetworkSnapshot.normalizeSsid(it).equals(currentSsid, ignoreCase = true)
                }
            ) return false
        }
        if (snapshot.isCaptivePortal) return false
        return true
    }

    fun buildEvaluationFingerprint(
        config: DnsSenseConfig,
        snapshot: NetworkSnapshot,
        tempBypassNetworkKey: String?
    ): String {
        val sortedSsids = config.bypassSsids
            .map { NetworkSnapshot.normalizeSsid(it).lowercase() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(",")
        return listOf(
            snapshot.networkKey,
            snapshot.isCaptivePortal.toString(),
            config.enabled.toString(),
            config.hostname.trim().lowercase(),
            config.autoProbeEnabled.toString(),
            config.bypassCellular.toString(),
            sortedSsids,
            tempBypassNetworkKey.orEmpty()
        ).joinToString("|")
    }
}
