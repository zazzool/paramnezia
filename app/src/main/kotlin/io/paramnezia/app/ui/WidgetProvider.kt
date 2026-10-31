package io.paramnezia.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import io.paramnezia.app.App
import io.paramnezia.app.R
import io.paramnezia.tunnel.TunnelRuntime
import io.paramnezia.tunnel.TunnelState

/**
 * Minimal 1×1 home-screen widget: a round VPN button coloured by state, and the traffic under it
 * while connected. Plain RemoteViews, so it costs
 * nothing until it is redrawn: on every tunnel state change and, while the tunnel is up and the
 * screen is on, every 30 seconds for the counters (see [App]).
 */
class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val app = context.applicationContext as App
            if (TunnelRuntime.state.value is TunnelState.Down || TunnelRuntime.state.value is TunnelState.Failed) app.connect() else app.disconnect()
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_TOGGLE = "io.paramnezia.app.WIDGET_TOGGLE"

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
            val click = if (VpnService.prepare(context) == null) {
                PendingIntent.getBroadcast(context, 0, Intent(context, WidgetProvider::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
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
