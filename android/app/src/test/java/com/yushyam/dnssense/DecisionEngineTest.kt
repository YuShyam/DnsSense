package com.yushyam.dnssense

import com.yushyam.dnssense.data.model.DecisionReason
import com.yushyam.dnssense.data.model.DnsSenseConfig
import com.yushyam.dnssense.data.model.HostnameValidator
import com.yushyam.dnssense.data.model.NetworkSnapshot
import com.yushyam.dnssense.data.model.NetworkTransport
import com.yushyam.dnssense.data.model.PrivateDnsMode
import com.yushyam.dnssense.data.model.ProbeResult
import com.yushyam.dnssense.data.repo.DnsSensePrefs
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
}
