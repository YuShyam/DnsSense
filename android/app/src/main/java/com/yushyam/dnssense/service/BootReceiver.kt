package com.yushyam.dnssense.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yushyam.dnssense.data.repo.DnsSensePrefs

/**
 * 開機自啟接收器 (Boot Completed Receiver)
 * 若使用者已啟用守護，開機後自動恢復背景網路感知。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = DnsSensePrefs(context)
            if (prefs.loadConfig().enabled) {
                DnsSenseService.startOrReevaluate(context)
            }
        }
    }
}
