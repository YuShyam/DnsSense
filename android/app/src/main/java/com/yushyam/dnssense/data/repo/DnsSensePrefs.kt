package com.yushyam.dnssense.data.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yushyam.dnssense.data.model.DnsSenseConfig
import com.yushyam.dnssense.data.model.EventLogItem
import com.yushyam.dnssense.data.model.NetworkSnapshot

/**
 * 防禦性設定持久化儲存庫 (Schema-Safe Preferences Repository)
 *
 * 所有 JSON 反序列化一律採用 JsonParser 逐欄安全解析，確保未來新增設定欄位時
 * 不會因 Gson 繞過 Kotlin 預設建構子而導致舊版設定遺失或拋出 NullPointerException。
 */
class DnsSensePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadConfig(): DnsSenseConfig {
        val raw = prefs.getString(KEY_CONFIG_JSON, null)
        return parseConfigJson(raw)
    }

    fun saveConfig(config: DnsSenseConfig) {
        val normalizedSsids = config.bypassSsids
            .map { NetworkSnapshot.normalizeSsid(it) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val clean = config.copy(
            hostname = config.hostname.trim(),
            bypassSsids = normalizedSsids
        )
        prefs.edit().putString(KEY_CONFIG_JSON, gson.toJson(clean)).apply()
    }

    fun addBypassSsid(ssid: String): DnsSenseConfig {
        val clean = NetworkSnapshot.normalizeSsid(ssid)
        val current = loadConfig()
        if (clean.isEmpty()) return current
        val updated = current.copy(bypassSsids = (current.bypassSsids + clean).distinctBy { it.lowercase() })
        saveConfig(updated)
        return updated
    }

    fun removeBypassSsid(ssid: String): DnsSenseConfig {
        val current = loadConfig()
        val updated = current.copy(
            bypassSsids = current.bypassSsids.filterNot { it.equals(ssid, ignoreCase = true) }
        )
        saveConfig(updated)
        return updated
    }

    fun getTempBypassNetworkKey(): String? = prefs.getString(KEY_TEMP_BYPASS_NET, null)

    fun setTempBypassNetworkKey(networkKey: String?) {
        if (networkKey.isNullOrBlank()) {
            prefs.edit().remove(KEY_TEMP_BYPASS_NET).apply()
        } else {
            prefs.edit().putString(KEY_TEMP_BYPASS_NET, networkKey).apply()
        }
    }

    fun loadLogs(): List<EventLogItem> {
        val raw = prefs.getString(KEY_EVENT_LOGS, null)
        return parseLogsJson(raw)
    }

    fun appendLog(item: EventLogItem) {
        val current = loadLogs().toMutableList()
        current.add(0, item)
        val trimmed = current.take(MAX_LOGS)
        prefs.edit().putString(KEY_EVENT_LOGS, gson.toJson(trimmed)).apply()
    }

    fun clearLogs() {
        prefs.edit().remove(KEY_EVENT_LOGS).apply()
    }

    companion object {
        private const val PREFS_NAME = "dnssense_prefs"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_TEMP_BYPASS_NET = "temp_bypass_network_key"
        private const val KEY_EVENT_LOGS = "event_logs_json"
        private const val MAX_LOGS = 40

        fun parseConfigJson(json: String?): DnsSenseConfig {
            if (json.isNullOrBlank()) return DnsSenseConfig()
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return DnsSenseConfig()
                val obj = root.asJsonObject
                DnsSenseConfig(
                    enabled = obj.optBoolean("enabled", true),
                    hostname = obj.optString("hostname", DnsSenseConfig.DEFAULT_HOSTNAME)
                        .ifBlank { DnsSenseConfig.DEFAULT_HOSTNAME },
                    autoProbeEnabled = obj.optBoolean("autoProbeEnabled", true),
                    bypassCellular = obj.optBoolean("bypassCellular", false),
                    bypassSsids = obj.optStringList("bypassSsids"),
                    themeMode = obj.optString("themeMode", "system").ifBlank { "system" }
                )
            } catch (_: Exception) {
                DnsSenseConfig()
            }
        }

        fun parseLogsJson(json: String?): List<EventLogItem> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonArray) return emptyList()
                root.asJsonArray.mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val obj = elem.asJsonObject
                    EventLogItem(
                        timestampMs = obj.optLong("timestampMs", 0L),
                        networkLabel = obj.optString("networkLabel", ""),
                        actionLabel = obj.optString("actionLabel", ""),
                        detail = obj.optString("detail", "")
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun JsonObject.optString(key: String, default: String): String {
            val e = get(key) ?: return default
            return if (e.isJsonNull || !e.isJsonPrimitive) default else e.asString
        }

        private fun JsonObject.optBoolean(key: String, default: Boolean): Boolean {
            val e = get(key) ?: return default
            return try {
                if (e.isJsonNull || !e.isJsonPrimitive) default else e.asBoolean
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.optLong(key: String, default: Long): Long {
            val e = get(key) ?: return default
            return try {
                if (e.isJsonNull || !e.isJsonPrimitive) default else e.asLong
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.optStringList(key: String): List<String> {
            val e = get(key) ?: return emptyList()
            if (e.isJsonNull || !e.isJsonArray) return emptyList()
            return e.asJsonArray.mapNotNull { item ->
                if (item.isJsonNull || !item.isJsonPrimitive) null else item.asString
            }
        }
    }
}
