package com.yushyam.dnssense

import com.yushyam.dnssense.data.model.DecisionReason
import com.yushyam.dnssense.data.model.DnsSenseConfig
import com.yushyam.dnssense.data.model.HostnameValidator
import com.yushyam.dnssense.data.model.NetworkSnapshot
import com.yushyam.dnssense.data.model.NetworkTransport
import com.yushyam.dnssense.data.model.PrivateDnsMode
import com.yushyam.dnssense.data.model.ProbeResult
import com.yushyam.dnssense.data.repo.DnsSensePrefs
import com.yushyam.dnssense.engine.AppUpdater
import com.yushyam.dnssense.engine.DecisionEngine
import com.yushyam.dnssense.engine.DotProber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DnsSense 核心雙層決策引擎與防衛性解析單元測試
 */
class DecisionEngineTest {

    private val baseConfig = DnsSenseConfig(
        enabled = true,
        hostname = "dns.adguard-dns.com",
        autoProbeEnabled = true,
        bypassCellular = false,
        bypassSsids = listOf("Office_Corp", "Home_PiHole")
    )

    @Test
    fun bypassWifiOverridesHealthyProbe() {
        val snapshot = NetworkSnapshot(NetworkTransport.WIFI, ssid = "\"Office_Corp\"")
        val outcome = DecisionEngine.evaluate(
            config = baseConfig,
            snapshot = snapshot,
            tempBypassNetworkKey = null,
            hasSecurePermission = true,
            probeResult = ProbeResult(isHealthy = true, latencyMs = 25, reason = "OK")
        )
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, outcome.targetMode)
        assertEquals(DecisionReason.BYPASS_WIFI_SSID, outcome.reason)
        assertFalse(outcome.isAdvisoryOnly)
        assertFalse(DecisionEngine.shouldRunDotProbe(baseConfig, snapshot, null))
    }

    @Test
    fun bypassCellularSwitchesToOpportunistic() {
        val config = baseConfig.copy(bypassCellular = true)
        val snapshot = NetworkSnapshot(NetworkTransport.CELLULAR)
        val outcome = DecisionEngine.evaluate(
            config = config,
            snapshot = snapshot,
            tempBypassNetworkKey = null,
            hasSecurePermission = true,
            probeResult = null
        )
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, outcome.targetMode)
        assertEquals(DecisionReason.BYPASS_CELLULAR, outcome.reason)
    }

    @Test
    fun captivePortalTriggersFallback() {
        val snapshot = NetworkSnapshot(NetworkTransport.WIFI, ssid = "Hotel_Free_WiFi", isCaptivePortal = true)
        val outcome = DecisionEngine.evaluate(
            config = baseConfig,
            snapshot = snapshot,
            tempBypassNetworkKey = null,
            hasSecurePermission = true,
            probeResult = null
        )
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, outcome.targetMode)
        assertEquals(DecisionReason.CAPTIVE_PORTAL_DETECTED, outcome.reason)
    }

    @Test
    fun failedDotProbeTriggersAutoFallback() {
        val snapshot = NetworkSnapshot(NetworkTransport.WIFI, ssid = "Cafe_Public")
        val outcome = DecisionEngine.evaluate(
            config = baseConfig,
            snapshot = snapshot,
            tempBypassNetworkKey = null,
            hasSecurePermission = true,
            probeResult = ProbeResult(isHealthy = false, latencyMs = 2500, reason = "TCP 853 逾時")
        )
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, outcome.targetMode)
        assertEquals(DecisionReason.DOT_PROBE_FAILED, outcome.reason)
    }

    @Test
    fun healthyDotProbeEnablesPrivateDns() {
        val snapshot = NetworkSnapshot(NetworkTransport.WIFI, ssid = "Cafe_Good")
        val outcome = DecisionEngine.evaluate(
            config = baseConfig,
            snapshot = snapshot,
            tempBypassNetworkKey = null,
            hasSecurePermission = true,
            probeResult = ProbeResult(isHealthy = true, latencyMs = 42, reason = "DoT 853 正常")
        )
        assertEquals(PrivateDnsMode.HOSTNAME, outcome.targetMode)
        assertEquals(DecisionReason.DOT_PROBE_HEALTHY, outcome.reason)
    }

    @Test
    fun tempManualBypassAppliesOnlyToMatchingNetwork() {
        val cafe = NetworkSnapshot(NetworkTransport.WIFI, ssid = "Cafe_A")
        val cellular = NetworkSnapshot(NetworkTransport.CELLULAR)

        val cafeOutcome = DecisionEngine.evaluate(
            config = baseConfig,
            snapshot = cafe,
            tempBypassNetworkKey = cafe.networkKey,
            hasSecurePermission = true,
            probeResult = null
        )
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, cafeOutcome.targetMode)
        assertEquals(DecisionReason.TEMP_MANUAL_BYPASS, cafeOutcome.reason)

        // 離開 Cafe_A 切到 4G/5G 時，單次覆寫自動失效
        val cellOutcome = DecisionEngine.evaluate(
            config = baseConfig,
            snapshot = cellular,
            tempBypassNetworkKey = cafe.networkKey,
            hasSecurePermission = true,
            probeResult = ProbeResult(isHealthy = true, latencyMs = 30, reason = "OK")
        )
        assertEquals(PrivateDnsMode.HOSTNAME, cellOutcome.targetMode)
    }

    @Test
    fun missingPermissionDegradesToMode3Advisory() {
        val snapshot = NetworkSnapshot(NetworkTransport.WIFI, ssid = "Office_Corp")
        val outcome = DecisionEngine.evaluate(
            config = baseConfig,
            snapshot = snapshot,
            tempBypassNetworkKey = null,
            hasSecurePermission = false,
            probeResult = null
        )
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, outcome.targetMode)
        assertTrue(outcome.isAdvisoryOnly)
    }

    @Test
    fun legacyPartialConfigJsonParsesSafely() {
        val legacyJson = """{"hostname":"dns.adguard-dns.com"}"""
        val parsed = DnsSensePrefs.parseConfigJson(legacyJson)
        assertTrue(parsed.enabled)
        assertTrue(parsed.autoProbeEnabled)
        assertFalse(parsed.bypassCellular)
        assertEquals("dns.adguard-dns.com", parsed.hostname)
        assertTrue(parsed.bypassSsids.isEmpty())
    }

    @Test
    fun hostnameValidatorRejectsMalformedInput() {
        assertTrue(HostnameValidator.isValid("dns.adguard-dns.com"))
        assertTrue(HostnameValidator.isValid("1dot1dot1dot1.cloudflare-dns.com"))
        assertFalse(HostnameValidator.isValid("https://dns.adguard-dns.com"))
        assertFalse(HostnameValidator.isValid("dns.adguard-dns.com; rm -rf /"))
        assertFalse(HostnameValidator.isValid("localhost"))
        assertFalse(HostnameValidator.isValid(""))
    }

    @Test
    fun dotQueryPacketHasValidDnsHeader() {
        val packet = DotProber.buildDnsAQuery(0x1234, "connectivitycheck.gstatic.com")
        assertTrue(packet.size > 12)
        assertEquals(0x12.toByte(), packet[0])
        assertEquals(0x34.toByte(), packet[1])
        assertEquals(0x01.toByte(), packet[2]) // RD=1
        assertEquals(0x01.toByte(), packet[5]) // QDCOUNT=1
    }

    @Test
    fun evaluationFingerprintDeduplicatesUnchangedNetwork() {
        val s1 = NetworkSnapshot(NetworkTransport.WIFI, ssid = "\"Office_Corp\"", isCaptivePortal = false)
        val s2 = NetworkSnapshot(NetworkTransport.WIFI, ssid = "Office_Corp", isCaptivePortal = false)
        val sCaptive = NetworkSnapshot(NetworkTransport.WIFI, ssid = "Office_Corp", isCaptivePortal = true)
        val sOther = NetworkSnapshot(NetworkTransport.CELLULAR)

        val fp1 = DecisionEngine.buildEvaluationFingerprint(baseConfig, s1, null)
        val fp2 = DecisionEngine.buildEvaluationFingerprint(baseConfig, s2, null)
        val fpCaptive = DecisionEngine.buildEvaluationFingerprint(baseConfig, sCaptive, null)
        val fpOther = DecisionEngine.buildEvaluationFingerprint(baseConfig, sOther, null)

        assertEquals(fp1, fp2)
        assertFalse(fp1 == fpCaptive)
        assertFalse(fp1 == fpOther)
    }

    @Test
    fun appUpdaterComparesSemanticVersionsAndParsesGitHubReleaseJson() {
        assertTrue(AppUpdater.isNewerVersion("0.2.0", "v0.2.1"))
        assertTrue(AppUpdater.isNewerVersion("v0.2.1", "v0.3.0"))
        assertTrue(AppUpdater.isNewerVersion("0.2.1", "v1.0.0"))
        assertFalse(AppUpdater.isNewerVersion("0.2.1", "v0.2.1"))
        assertFalse(AppUpdater.isNewerVersion("0.3.0", "v0.2.9"))

        val sampleJson = """
            {
              "tag_name": "v0.2.1",
              "body": "新增 GitHub 線上自動更新功能",
              "assets": [
                {
                  "name": "DnsSense-v0.2.1.apk",
                  "browser_download_url": "https://github.com/YuShyam/DnsSense/releases/download/v0.2.1/DnsSense-v0.2.1.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdater.parseReleaseJson("0.2.0", sampleJson).getOrThrow()
        assertTrue(parsed.isUpdateAvailable)
        assertEquals("0.2.0", parsed.currentVersion)
        assertEquals("0.2.1", parsed.latestVersion)
        assertEquals("DnsSense-v0.2.1.apk", parsed.assetName)
        assertTrue(parsed.downloadUrl.endsWith("DnsSense-v0.2.1.apk"))
    }
}
