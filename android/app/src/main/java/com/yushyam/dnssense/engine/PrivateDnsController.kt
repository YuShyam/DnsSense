package com.yushyam.dnssense.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.yushyam.dnssense.data.model.HostnameValidator
import com.yushyam.dnssense.data.model.PrivateDnsMode
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 系統私人 DNS 控制器與權限管理器 (Native Private DNS Controller)
 *
 * 模式 1：具備 WRITE_SECURE_SETTINGS 權限時直接讀寫 Settings.Global。
 * 模式 3：未授權時提供一鍵跳轉系統網路設定頁之 Intent 與 ADB / Shizuku 授權引導。
 */
object PrivateDnsController {

    private const val KEY_PRIVATE_DNS_MODE = "private_dns_mode"
    private const val KEY_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun readCurrentMode(context: Context): PrivateDnsMode {
        return try {
            val raw = Settings.Global.getString(context.contentResolver, KEY_PRIVATE_DNS_MODE)
            PrivateDnsMode.fromSetting(raw)
        } catch (_: Exception) {
            PrivateDnsMode.UNKNOWN
        }
    }

    fun readCurrentSpecifier(context: Context): String {
        return try {
            Settings.Global.getString(context.contentResolver, KEY_PRIVATE_DNS_SPECIFIER) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 將目標私人 DNS 模式套用至系統設定
     * Applies the requested Private DNS mode to Settings.Global.
     */
    fun applyMode(
        context: Context,
        targetMode: PrivateDnsMode,
        hostname: String? = null
    ): Pair<Boolean, String> {
        if (!hasWriteSecureSettings(context)) {
            return false to "尚未授予自動切換權限（目前為提醒模式）"
        }

        if (targetMode == PrivateDnsMode.HOSTNAME) {
            val cleanHost = hostname?.trim().orEmpty()
            if (!HostnameValidator.isValid(cleanHost)) {
                return false to "私人 DNS 主機名稱格式無效：$cleanHost"
            }
            return try {
                Settings.Global.putString(context.contentResolver, KEY_PRIVATE_DNS_SPECIFIER, cleanHost)
                Settings.Global.putString(context.contentResolver, KEY_PRIVATE_DNS_MODE, targetMode.settingValue)
                true to "已切換為私人 DNS ($cleanHost)"
            } catch (e: SecurityException) {
                false to "系統拒絕寫入設定：${e.message}"
            } catch (e: Exception) {
                false to "切換失敗：${e.message}"
            }
        }

        return try {
            Settings.Global.putString(context.contentResolver, KEY_PRIVATE_DNS_MODE, targetMode.settingValue)
            true to "已切換為 ${targetMode.labelZh}"
        } catch (e: SecurityException) {
            false to "系統拒絕寫入設定：${e.message}"
        } catch (e: Exception) {
            false to "切換失敗：${e.message}"
        }
    }

    /**
     * 產生電腦端一次性 ADB 授權指令
     * Generates the one-time ADB command for permanent WRITE_SECURE_SETTINGS grant.
     */
    fun adbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
    }

    /**
     * 透過 Shizuku 執行一次性權限自我授予（完成後無需保持 Shizuku 運作）
     * Uses Shizuku once to execute pm grant with hardcoded arguments (zero command injection).
     */
    fun grantViaShizuku(context: Context): Pair<Boolean, String> {
        if (hasWriteSecureSettings(context)) {
            return true to "已具備全自動切換權限"
        }
        return try {
            if (!Shizuku.pingBinder()) {
                return false to "尚未偵測到運作中的 Shizuku 服務"
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(2001)
                return false to "已向 Shizuku 請求授權，請核准後再次點擊"
            }

            // 採固定參數陣列呼叫 pm grant，杜絕任何指令注入風險
            val cmd = arrayOf(
                "pm",
                "grant",
                context.packageName,
                Manifest.permission.WRITE_SECURE_SETTINGS
            )
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, cmd, null, null) as ShizukuRemoteProcess
            val err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()
            process.destroy()

            if (exitCode == 0 && hasWriteSecureSettings(context)) {
                true to "Shizuku 一次授權成功！後續即使關閉 Shizuku 或重開機仍永久有效"
            } else {
                false to "Shizuku 授權未完成 (exit=$exitCode ${if (err.isNotEmpty()) err else ""})"
            }
        } catch (e: Throwable) {
            false to "Shizuku 呼叫失敗：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 建構開啟系統網路設定頁之 Intent（供模式 3 降級提醒使用）
     */
    fun buildNetworkSettingsIntent(): Intent {
        return Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
