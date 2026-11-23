package com.yushyam.dnssense.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yushyam.dnssense.MainActivity
import com.yushyam.dnssense.R
import com.yushyam.dnssense.data.model.DecisionOutcome
import com.yushyam.dnssense.data.model.EventLogItem
import com.yushyam.dnssense.data.model.NetworkSnapshot
import com.yushyam.dnssense.data.model.NetworkTransport
import com.yushyam.dnssense.data.model.PrivateDnsMode
import com.yushyam.dnssense.data.repo.DnsSensePrefs
import com.yushyam.dnssense.engine.DecisionEngine
import com.yushyam.dnssense.engine.DotProber
import com.yushyam.dnssense.engine.NetworkObserver
import com.yushyam.dnssense.engine.PrivateDnsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DnsSense 背景網路監聽前景服務 (Background Foreground Service)
 */
class DnsSenseService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: DnsSensePrefs
    private lateinit var observer: NetworkObserver
    private var evalJob: Job? = null
    @Volatile
    private var lastEvaluatedFingerprint: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = DnsSensePrefs(this)
        observer = NetworkObserver(this)
        ensureChannels()
        startForeground(NOTIF_GUARD_ID, buildGuardNotification("正在檢查網路與 DNS 狀態..."))
        observer.start { snapshot ->
            scheduleEvaluation(snapshot, immediate = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ADD_WIFI_BYPASS -> {
                val ssid = intent.getStringExtra(EXTRA_SSID)
                if (!ssid.isNullOrBlank()) {
                    prefs.addBypassSsid(ssid)
                    prefs.appendLog(
                        EventLogItem(
                            timestampMs = System.currentTimeMillis(),
                            networkLabel = "Wi-Fi: $ssid",
                            actionLabel = "加入排除",
                            detail = "已從通知將「$ssid」加入排除清單"
                        )
                    )
                }
                scheduleEvaluation(observer.currentSnapshot(), immediate = true)
            }
            else -> {
                scheduleEvaluation(observer.currentSnapshot(), immediate = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleEvaluation(snapshot: NetworkSnapshot, immediate: Boolean) {
        evalJob?.cancel()
        evalJob = serviceScope.launch {
            if (!immediate) {
                delay(DEBOUNCE_MS)
            }
            evaluateAndAct(snapshot, force = immediate)
        }
    }

    private fun evaluateAndAct(snapshot: NetworkSnapshot, force: Boolean) {
        val config = prefs.loadConfig()
        var tempBypassKey = prefs.getTempBypassNetworkKey()

        if (!tempBypassKey.isNullOrBlank() &&
            snapshot.transport != NetworkTransport.NONE &&
            tempBypassKey != snapshot.networkKey
        ) {
            prefs.setTempBypassNetworkKey(null)
            tempBypassKey = null
        }

        val fingerprint = DecisionEngine.buildEvaluationFingerprint(config, snapshot, tempBypassKey)
        if (!force && fingerprint == lastEvaluatedFingerprint) {
            return
        }
        lastEvaluatedFingerprint = fingerprint

        val hasPermission = PrivateDnsController.hasWriteSecureSettings(this)
        val probeResult = if (DecisionEngine.shouldRunDotProbe(config, snapshot, tempBypassKey)) {
            DotProber.probe(config.hostname)
        } else {
            null
        }

        val outcome = DecisionEngine.evaluate(
            config = config,
            snapshot = snapshot,
            tempBypassNetworkKey = tempBypassKey,
            hasSecurePermission = hasPermission,
            probeResult = probeResult
        )

        val currentMode = PrivateDnsController.readCurrentMode(this)
        val targetMode = outcome.targetMode

        if (targetMode == null) {
            updateGuardNotification("${snapshot.displayLabel} · ${outcome.reason.labelZh}")
            broadcastStateUpdated()
            return
        }

        val currentHost = PrivateDnsController.readCurrentSpecifier(this)
        val alreadyMatches = (currentMode == targetMode) &&
            (targetMode != PrivateDnsMode.HOSTNAME || currentHost.equals(config.hostname, ignoreCase = true))

        if (outcome.isAdvisoryOnly) {
            val modeSummary = "${snapshot.displayLabel} · 建議：${targetMode.labelZh}（提醒模式）"
            updateGuardNotification(modeSummary)
            if (!alreadyMatches) {
                showAdvisoryNotification(snapshot, outcome)
                prefs.appendLog(
                    EventLogItem(
                        timestampMs = System.currentTimeMillis(),
                        networkLabel = snapshot.displayLabel,
                        actionLabel = "提醒切換",
                        detail = "建議切為 ${targetMode.labelZh}（${outcome.detailZh}）"
                    )
                )
            }
        } else {
            if (!alreadyMatches) {
                val (ok, msg) = PrivateDnsController.applyMode(this, targetMode, config.hostname)
                prefs.appendLog(
                    EventLogItem(
                        timestampMs = System.currentTimeMillis(),
                        networkLabel = snapshot.displayLabel,
                        actionLabel = if (ok) "自動 ⮕ ${targetMode.labelZh}" else "切換失敗",
                        detail = if (ok) outcome.detailZh else msg
                    )
                )
                if (ok && targetMode == PrivateDnsMode.OPPORTUNISTIC &&
                    snapshot.transport == NetworkTransport.WIFI &&
                    !snapshot.ssid.isNullOrBlank()
                ) {
                    showFallbackInfoNotification(snapshot, outcome)
                }
            }
            updateGuardNotification("${snapshot.displayLabel} · 目前：${targetMode.labelZh}")
        }

        broadcastStateUpdated()
    }

    private fun broadcastStateUpdated() {
        sendBroadcast(Intent(ACTION_STATE_UPDATED).setPackage(packageName))
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val guardChannel = NotificationChannel(
                CHANNEL_GUARD,
                "DnsSense 常駐狀態",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "顯示目前網路與私人 DNS 狀態"
                setShowBadge(false)
            }
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "DNS 切換通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "提醒模式建議通知與自動切換提示"
            }
            nm.createNotificationChannel(guardChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun buildGuardNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_dnssense_status)
            .setContentTitle("DnsSense 運作中")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateGuardNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_GUARD_ID, buildGuardNotification(statusText))
    }

    private fun showAdvisoryNotification(snapshot: NetworkSnapshot, outcome: DecisionOutcome) {
        val settingsPending = PendingIntent.getActivity(
            this,
            201,
            PrivateDnsController.buildNetworkSettingsIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val targetLabel = outcome.targetMode?.labelZh ?: "自動 DNS"
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_dnssense_status)
            .setContentTitle("建議切換為：$targetLabel")
            .setContentText("${snapshot.displayLabel} — ${outcome.detailZh}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${snapshot.displayLabel}\n${outcome.detailZh}\n點擊開啟系統設定手動切換。"))
            .setAutoCancel(true)
            .setContentIntent(settingsPending)
            .addAction(0, "開啟系統設定", settingsPending)

        addBypassActionIfWifi(builder, snapshot)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT_ID, builder.build())
    }

    private fun showFallbackInfoNotification(snapshot: NetworkSnapshot, outcome: DecisionOutcome) {
        val openAppPending = PendingIntent.getActivity(
            this,
            202,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_dnssense_status)
            .setContentTitle("已自動切回系統預設 DNS")
            .setContentText("${snapshot.displayLabel} — ${outcome.detailZh}")
            .setAutoCancel(true)
            .setContentIntent(openAppPending)

        addBypassActionIfWifi(builder, snapshot)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT_ID, builder.build())
    }

    private fun addBypassActionIfWifi(
        builder: NotificationCompat.Builder,
        snapshot: NetworkSnapshot
    ) {
        val ssid = NetworkSnapshot.normalizeSsid(snapshot.ssid)
        if (snapshot.transport == NetworkTransport.WIFI && ssid.isNotEmpty()) {
            val addIntent = Intent(this, DnsSenseService::class.java).apply {
                action = ACTION_ADD_WIFI_BYPASS
                putExtra(EXTRA_SSID, ssid)
            }
            val pending = PendingIntent.getService(
                this,
                203,
                addIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "加入排除清單", pending)
        }
    }

    companion object {
        const val ACTION_REEVALUATE = "com.yushyam.dnssense.ACTION_REEVALUATE"
        const val ACTION_STOP = "com.yushyam.dnssense.ACTION_STOP"
        const val ACTION_ADD_WIFI_BYPASS = "com.yushyam.dnssense.ACTION_ADD_WIFI_BYPASS"
        const val ACTION_STATE_UPDATED = "com.yushyam.dnssense.ACTION_STATE_UPDATED"
        const val EXTRA_SSID = "extra_ssid"

        private const val CHANNEL_GUARD = "dnssense_guard"
        private const val CHANNEL_ALERT = "dnssense_alert"
        private const val NOTIF_GUARD_ID = 1001
        private const val NOTIF_ALERT_ID = 1002
        private const val DEBOUNCE_MS = 1500L

        fun startOrReevaluate(context: Context) {
            val intent = Intent(context, DnsSenseService::class.java).apply {
                action = ACTION_REEVALUATE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsSenseService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
