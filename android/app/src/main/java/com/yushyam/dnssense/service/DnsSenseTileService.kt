package com.yushyam.dnssense.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.yushyam.dnssense.data.model.NetworkTransport
import com.yushyam.dnssense.data.repo.DnsSensePrefs
import com.yushyam.dnssense.engine.NetworkObserver
import com.yushyam.dnssense.engine.PrivateDnsController

/**
 * 下拉快速設定磚 (Quick Settings Escape Hatch Tile)
 *
 * 專為「853 探測正常但局部功能被廣告規則誤殺」場景設計：
 * 點一下立即將「本次連線」暫時切回自動 DNS；離開此網路後自動恢復私人 DNS 守護。
 */
@RequiresApi(Build.VERSION_CODES.N)
class DnsSenseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = DnsSensePrefs(this)
        val snapshot = NetworkObserver(this).currentSnapshot()

        if (snapshot.transport == NetworkTransport.NONE) {
            Toast.makeText(this, "DnsSense: 目前無網路連線", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTemp = prefs.getTempBypassNetworkKey()
        if (currentTemp == snapshot.networkKey) {
            prefs.setTempBypassNetworkKey(null)
            Toast.makeText(this, "DnsSense: 已恢復私人 DNS 守護", Toast.LENGTH_SHORT).show()
        } else {
            prefs.setTempBypassNetworkKey(snapshot.networkKey)
            val suffix = if (PrivateDnsController.hasWriteSecureSettings(this)) {
                "已暫時切為自動 DNS（離開網路自動恢復）"
            } else {
                "已標記本次連線暫停守護（請於系統設定手動切換）"
            }
            Toast.makeText(this, "DnsSense: $suffix", Toast.LENGTH_SHORT).show()
        }

        DnsSenseService.startOrReevaluate(this)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val prefs = DnsSensePrefs(this)
        val snapshot = NetworkObserver(this).currentSnapshot()
        val isTempBypass = !prefs.getTempBypassNetworkKey().isNullOrBlank() &&
            prefs.getTempBypassNetworkKey() == snapshot.networkKey

        if (isTempBypass) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "DNS: 暫時自動"
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "DnsSense 守護"
        }
        tile.updateTile()
    }
}
