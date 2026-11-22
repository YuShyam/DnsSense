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
 *
 * 決策優先順序：
 * 1. 守護開關 / 無網路 / 主機名稱校驗
 * 2. 單次連線暫時覆寫（下拉快速磚觸發，離開該網路自動歸零）
 * 3. 手動排除清單（行動數據 4G/5G 或指定 Wi-Fi SSID）— 永遠優先於自動探測
 * 4. 系統 Captive Portal 認證頁偵測
 * 5. 主動 DoT 853 探測結果（失敗即自動降級為系統自動 DNS）
 * 6. 依據是否擁有 WRITE_SECURE_SETTINGS 自動分流模式 1（自動寫入）與模式 3（通知提醒）
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
                detailZh = "DnsSense 背景守護已停用，維持系統現狀",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (snapshot.transport == NetworkTransport.NONE) {
            return DecisionOutcome(
                targetMode = null,
                reason = DecisionReason.NO_NETWORK,
                detailZh = "目前無有效網路連線，暫不調整 DNS",
                isAdvisoryOnly = advisoryOnly
            )
        }

        if (!HostnameValidator.isValid(config.hostname)) {
            return DecisionOutcome(
                targetMode = null,
                reason = DecisionReason.INVALID_HOSTNAME,
                detailZh = "私人 DNS 主機名稱無效：${config.hostname}",
                isAdvisoryOnly = advisoryOnly
            )
        }

        // 第一層：單次手動暫時覆寫
        if (!tempBypassNetworkKey.isNullOrBlank() && tempBypassNetworkKey == snapshot.networkKey) {
            return DecisionOutcome(
                targetMode = PrivateDnsMode.OPPORTUNISTIC,
                reason = DecisionReason.TEMP_MANUAL_BYPASS,
                detailZh = "已對此連線暫時啟用自動 DNS，離開後將自動恢復私人 DNS",
                isAdvisoryOnly = advisoryOnly
            )
        }

        // 第一層：手動排除清單（最高規則優先權，專治 853 通但內網或局部功能異常）
        if (snapshot.transport == NetworkTransport.CELLULAR && config.bypassCellular) {
            return DecisionOutcome(
                targetMode = PrivateDnsMode.OPPORTUNISTIC,
                reason = DecisionReason.BYPASS_CELLULAR,
                detailZh = "行動數據 (4G/5G) 位於排除清單，切換為系統自動 DNS",
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
                    detailZh = "Wi-Fi「$currentSsid」位於排除網路清單，切換為系統自動 DNS",
                    isAdvisoryOnly = advisoryOnly
                )
            }
        }

        // 第二層：Captive Portal（飯店／高鐵／咖啡廳網頁認證）強制降級
        if (snapshot.isCaptivePortal) {
            return DecisionOutcome(
                targetMode = PrivateDnsMode.OPPORTUNISTIC,
                reason = DecisionReason.CAPTIVE_PORTAL_DETECTED,
                detailZh = "偵測到網路需要網頁認證，暫時切換為自動 DNS 以利跳轉登入頁",
                isAdvisoryOnly = advisoryOnly
            )
        }

        // 第二層：主動 DoT 853 探測判定
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
                    detailZh = "${probeResult.reason}，自動降級為系統自動 DNS",
                    isAdvisoryOnly = advisoryOnly
                )
            }
        }

        // 預設：非排除網路一律啟用私人 DNS 擋廣告
        return DecisionOutcome(
            targetMode = PrivateDnsMode.HOSTNAME,
            reason = DecisionReason.DEFAULT_PRIVATE_DNS,
            detailZh = "非排除網路，啟用私人 DNS (${config.hostname})",
            isAdvisoryOnly = advisoryOnly
        )
    }

    /**
     * 判斷在當前條件下是否需要發起實際 DoT 853 網路探測
     * Avoids redundant network probes when manual bypass or captive portal already decides.
     */
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
}
