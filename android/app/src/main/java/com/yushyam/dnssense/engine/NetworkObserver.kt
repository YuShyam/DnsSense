package com.yushyam.dnssense.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.yushyam.dnssense.data.model.NetworkSnapshot
import com.yushyam.dnssense.data.model.NetworkTransport

/**
 * 跨版本網路狀態與 Captive Portal 感知器 (Active Network & SSID Observer)
 *
 * 相容 Android 8~14：優先從 NetworkCapabilities.transportInfo 取得 WifiInfo，
 * 必要時安全降級讀取 WifiManager.connectionInfo。
 */
class NetworkObserver(private val context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastDispatchedSnapshot: NetworkSnapshot? = null

    fun currentSnapshot(): NetworkSnapshot {
        return try {
            val activeNet = connectivityManager.activeNetwork ?: return NetworkSnapshot(NetworkTransport.NONE)
            val caps = connectivityManager.getNetworkCapabilities(activeNet)
                ?: return NetworkSnapshot(NetworkTransport.NONE)
            buildSnapshot(caps)
        } catch (_: Exception) {
            NetworkSnapshot(NetworkTransport.NONE)
        }
    }

    fun start(onChanged: (NetworkSnapshot) -> Unit) {
        stop()
        lastDispatchedSnapshot = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatchIfChanged(currentSnapshot(), onChanged)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                dispatchIfChanged(buildSnapshot(networkCapabilities), onChanged)
            }

            override fun onLost(network: Network) {
                dispatchIfChanged(currentSnapshot(), onChanged)
            }
        }
        callback = cb

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(cb)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, cb)
            }
        } catch (_: Exception) {
            // 忽略系統偶發回呼註冊限制
        }
    }

    fun stop() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
                // ignore
            }
        }
        callback = null
        lastDispatchedSnapshot = null
    }

    /**
     * 源頭節流：僅當傳輸類型、SSID 或 Captive Portal 狀態實質改變時才回報，
     * 過濾 Wi-Fi 訊號強弱 (RSSI) 波動引發之無效喚醒。
     */
    private fun dispatchIfChanged(
        snapshot: NetworkSnapshot,
        onChanged: (NetworkSnapshot) -> Unit
    ) {
        val prev = lastDispatchedSnapshot
        if (prev != null &&
            prev.networkKey == snapshot.networkKey &&
            prev.isCaptivePortal == snapshot.isCaptivePortal
        ) {
            return
        }
        lastDispatchedSnapshot = snapshot
        onChanged(snapshot)
    }

    @Suppress("DEPRECATION")
    private fun buildSnapshot(caps: NetworkCapabilities): NetworkSnapshot {
        val isCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssidFromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (caps.transportInfo as? WifiInfo)?.ssid
                } else {
                    null
                }
                val cleanCapsSsid = NetworkSnapshot.normalizeSsid(ssidFromCaps)
                val finalSsid = if (cleanCapsSsid.isNotEmpty()) {
                    cleanCapsSsid
                } else {
                    try {
                        NetworkSnapshot.normalizeSsid(wifiManager.connectionInfo?.ssid)
                    } catch (_: Exception) {
                        ""
                    }
                }
                NetworkSnapshot(
                    transport = NetworkTransport.WIFI,
                    ssid = finalSsid.ifEmpty { null },
                    isCaptivePortal = isCaptive
                )
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                NetworkSnapshot(
                    transport = NetworkTransport.CELLULAR,
                    isCaptivePortal = isCaptive
                )
            }
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> {
                NetworkSnapshot(
                    transport = NetworkTransport.OTHER,
                    isCaptivePortal = isCaptive
                )
            }
            else -> NetworkSnapshot(NetworkTransport.NONE)
        }
    }
}
