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
 * 傳輸網路類型 (Active Network Transport)
 */
enum class NetworkTransport(val labelZh: String) {
    WIFI("Wi-Fi"),
    CELLULAR("行動網路 (4G/5G)"),
    OTHER("其他網路"),
    NONE("無網路")
}

/**
 * 目前網路狀態快照 (Active Network Snapshot)
 */
data class NetworkSnapshot(
    val transport: NetworkTransport,
    val ssid: String? = null,
    val isCaptivePortal: Boolean = false
) {
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
 * DoT 853 連線測試結果 (Active DoT Probe Result)
 */
data class ProbeResult(
    val isHealthy: Boolean,
    val latencyMs: Long = 0L,
    val reason: String = ""
)

/**
 * 外觀深淺色主題模式 (App Theme Mode)
 */
enum class ThemeMode(val settingValue: String, val labelZh: String) {
    SYSTEM("system", "跟隨系統"),
    LIGHT("light", "淺色"),
    DARK("dark", "深色");

    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }

    companion object {
        fun fromSetting(raw: String?): ThemeMode = when (raw?.lowercase()) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}

/**
 * 使用者設定與排除規則 (User Configuration & Bypass Rules)
 */
data class DnsSenseConfig(
    val enabled: Boolean = true,
    val hostname: String = DEFAULT_HOSTNAME,
    val autoProbeEnabled: Boolean = true,
    val bypassCellular: Boolean = false,
    val bypassSsids: List<String> = emptyList(),
    val themeMode: String = ThemeMode.SYSTEM.settingValue
) {
    companion object {
        const val DEFAULT_HOSTNAME = "dns.adguard-dns.com"
    }
}

/**
 * 決策依據原因 (Decision Reason)
 */
enum class DecisionReason(val labelZh: String) {
    GUARD_DISABLED("自動切換已暫停"),
    NO_NETWORK("目前無網路連線"),
    INVALID_HOSTNAME("伺服器位址格式無效"),
    TEMP_MANUAL_BYPASS("單次連線暫時切換為自動 DNS"),
    BYPASS_CELLULAR("排除規則：行動網路 (4G/5G)"),
    BYPASS_WIFI_SSID("排除規則：位於排除 Wi-Fi 清單"),
    CAPTIVE_PORTAL_DETECTED("網路需網頁登入認證"),
    DOT_PROBE_FAILED("853 埠連線失敗，自動切回預設 DNS"),
    DOT_PROBE_HEALTHY("853 埠連線正常，啟用私人 DNS"),
    DEFAULT_PRIVATE_DNS("一般網路，啟用私人 DNS")
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
 * 私人 DNS 伺服器位址格式校驗器 (Strict FQDN Validator)
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
