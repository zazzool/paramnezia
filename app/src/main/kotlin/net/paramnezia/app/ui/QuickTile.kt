package net.paramnezia.app.ui

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import net.paramnezia.app.App
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState

/** Quick Settings tile: one tap on and off, without opening the app. */
class QuickTile : TileService() {
    private val app get() = application as App

    override fun onStartListening() = render()

    override fun onClick() {
        val up = TunnelRuntime.state.value !is TunnelState.Down
        when {
            up -> app.disconnect()
            app.vpnPermissionIntent() == null -> app.connect()
            else -> openApp()
        }
        render()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val s = TunnelRuntime.state.value
        tile.state = if (s is TunnelState.Up || s is TunnelState.Connecting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = (s as? TunnelState.Up)?.serverName
        tile.updateTile()
    }
}
