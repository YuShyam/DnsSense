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

    fun applyMode(
        context: Context,
        targetMode: PrivateDnsMode,
        hostname: String? = null
    ): Pair<Boolean, String> {
        if (!hasWriteSecureSettings(context)) {
            return false to "尚未開啟自動切換權限（目前為提醒模式）"
        }

        if (targetMode == PrivateDnsMode.HOSTNAME) {
            val cleanHost = hostname?.trim().orEmpty()
            if (!HostnameValidator.isValid(cleanHost)) {
                return false to "私人 DNS 位址格式無效：$cleanHost"
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

    fun adbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
    }

    fun grantViaShizuku(context: Context): Pair<Boolean, String> {
        if (hasWriteSecureSettings(context)) {
            return true to "已開啟自動切換權限"
        }
        return try {
            if (!Shizuku.pingBinder()) {
                return false to "未偵測到運作中的 Shizuku 服務"
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(2001)
                return false to "已向 Shizuku 請求授權，請允許後再點一次"
            }

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
                true to "Shizuku 授權成功！已啟用自動切換模式"
            } else {
                false to "Shizuku 授權失敗 (exit=$exitCode ${if (err.isNotEmpty()) err else ""})"
            }
        } catch (e: Throwable) {
            false to "Shizuku 呼叫失敗：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun buildNetworkSettingsIntent(): Intent {
        return Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
