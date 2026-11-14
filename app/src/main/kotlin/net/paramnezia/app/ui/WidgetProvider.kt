package net.paramnezia.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import net.paramnezia.app.App
import net.paramnezia.app.R
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState

/**
 * Minimal 1×1 home-screen widget: a round VPN button coloured by state, and the traffic under it
 * while connected. Plain RemoteViews, so it costs
 * nothing until it is redrawn: on every tunnel state change and, while the tunnel is up and the
 * screen is on, every 30 seconds for the counters (see [App]).
 */
class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WidgetProvider::class.java)).isNotEmpty()

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val state = TunnelRuntime.state.value
            val views = RemoteViews(context.packageName, R.layout.widget)
            val stats = if (state is TunnelState.Up) TunnelRuntime.stats() else null
            val label = when (state) {
                is TunnelState.Up -> stats?.let { formatBytes(it.rxBytes + it.txBytes) } ?: "ВКЛ"
                is TunnelState.Connecting -> "…"
                else -> null
            }
            views.setTextViewText(R.id.widget_traffic, label.orEmpty())
            views.setViewVisibility(R.id.widget_traffic, if (label == null) android.view.View.GONE else android.view.View.VISIBLE)
            views.setInt(R.id.widget_toggle, "setBackgroundResource", when (state) {
                is TunnelState.Up -> R.drawable.widget_button_on
                is TunnelState.Connecting -> R.drawable.widget_button_connecting
                else -> R.drawable.widget_button_off
            })

            // Without VPN permission the first tap has to open the app to ask for it.
            // The toggle is an invisible activity, not a broadcast: Android lets an app start its
            // service from an activity the user tapped, but not always from a background receiver.
            val click = if (VpnService.prepare(context) == null) {
                PendingIntent.getActivity(context, 2, Intent(context, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            } else {
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            }
            views.setOnClickPendingIntent(R.id.widget_toggle, click)
            views.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(ids, views)
        }
    }
}

/** The widget's toggle: no UI, switches the tunnel and closes at once. */
class ToggleActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        val s = TunnelRuntime.state.value
        if (s is TunnelState.Down || (s is TunnelState.Failed && s.retryAt == 0L)) {
            if (VpnService.prepare(this) == null) app.connect()
            else startActivity(Intent(this, MainActivity::class.java))
        } else {
            app.disconnect()
        }
        finish()
    }
}
