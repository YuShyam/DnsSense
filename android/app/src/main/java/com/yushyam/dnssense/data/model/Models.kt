package com.yushyam.dnssense.data.model

/**
 * 系統私人 DNS 模式列舉 (Android Settings.Global private_dns_mode)
 */
enum class PrivateDnsMode(val settingValue: String, val labelZh: String) {
    OFF("off", "已關閉"),
    OPPORTUNISTIC("opportunistic", "自動 DNS"),
    HOSTNAME("hostname", "私人 DNS"),
    UNKNOWN("unknown", "未知");

    companion object {
        fun fromSetting(raw: String?): PrivateDnsMode = when (raw?.lowercase()) {
            "off" -> OFF
            "opportunistic", null, "" -> OPPORTUNISTIC
            "hostname" -> HOSTNAME
            else -> UNKNOWN
        }
    }
}

/**
 * 當前傳輸網路類型 (Active Network Transport)
 */
enum class NetworkTransport(val labelZh: String) {
    WIFI("Wi-Fi"),
    CELLULAR("行動數據 (4G/5G)"),
    OTHER("其他網路"),
    NONE("無網路")
}

/**
 * 當前網路狀態快照 (Active Network Snapshot)
 */
data class NetworkSnapshot(
    val transport: NetworkTransport,
    val ssid: String? = null,
    val isCaptivePortal: Boolean = false
) {
    /**
     * 產生用於單次覆寫與事件記錄之網路特徵鍵
     * Generates a stable key representing the current network identity.
     */
    val networkKey: String
        get() = when (transport) {
            NetworkTransport.WIFI -> "wifi:${normalizeSsid(ssid).ifEmpty { "<unknown>" }}"
            NetworkTransport.CELLULAR -> "cellular"
            NetworkTransport.OTHER -> "other"
            NetworkTransport.NONE -> "none"
        }

    val displayLabel: String
        get() = when (transport) {
            NetworkTransport.WIFI -> {
                val clean = normalizeSsid(ssid)
                if (clean.isNotEmpty()) "Wi-Fi: $clean" else "Wi-Fi（未取得名稱）"
            }
            else -> transport.labelZh
        }

    companion object {
        fun normalizeSsid(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val trimmed = raw.trim().removeSurrounding("\"")
            return if (trimmed.equals("<unknown ssid>", ignoreCase = true)) "" else trimmed
        }
    }
}

/**
 * DoT 853 主動探測結果 (Active DoT Probe Result)
 */
data class ProbeResult(
    val isHealthy: Boolean,
    val latencyMs: Long = 0L,
    val reason: String = ""
)

/**
 * 使用者配置與排除規則 (User Configuration & Bypass Rules)
 */
data class DnsSenseConfig(
    val enabled: Boolean = true,
    val hostname: String = DEFAULT_HOSTNAME,
    val autoProbeEnabled: Boolean = true,
    val bypassCellular: Boolean = false,
    val bypassSsids: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_HOSTNAME = "dns.adguard-dns.com"
    }
}

/**
 * 決策依據原因 (Decision Reason)
 */
enum class DecisionReason(val labelZh: String) {
    GUARD_DISABLED("守護已暫停"),
    NO_NETWORK("目前無網路連線"),
    INVALID_HOSTNAME("私人 DNS 主機格式無效"),
    TEMP_MANUAL_BYPASS("單次連線暫時切換為自動 DNS"),
    BYPASS_CELLULAR("命中排除規則：行動數據 (4G/5G)"),
    BYPASS_WIFI_SSID("命中排除網路清單"),
    CAPTIVE_PORTAL_DETECTED("偵測到需網頁認證（Captive Portal）"),
    DOT_PROBE_FAILED("DoT 853 探測失敗，自動降級"),
    DOT_PROBE_HEALTHY("DoT 853 探測健康，啟用私人 DNS"),
    DEFAULT_PRIVATE_DNS("非排除網路，啟用私人 DNS")
}

/**
 * 雙層決策引擎輸出 (Decision Engine Output)
 */
data class DecisionOutcome(
    val targetMode: PrivateDnsMode?,
    val reason: DecisionReason,
    val detailZh: String,
    val isAdvisoryOnly: Boolean
)

/**
 * 狀態與切換歷史記錄 (Event Log Item)
 */
data class EventLogItem(
    val timestampMs: Long,
    val networkLabel: String,
    val actionLabel: String,
    val detail: String
)

/**
 * 私人 DNS 主機名稱格式校驗器 (Strict FQDN Validator)
 * 防止非法字串寫入系統設定或引發意外異常。
 */
object HostnameValidator {
    private val FQDN_REGEX = Regex(
        "^(?=.{1,253}$)(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))+$"
    )

    fun isValid(hostname: String?): Boolean {
        if (hostname.isNullOrBlank()) return false
        return FQDN_REGEX.matches(hostname.trim())
    }
}
