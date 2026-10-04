package dev.jevassist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: a round mic button. Tapping it opens the assistant overlay, which starts
 * listening right away, the battery-free alternative to the always-on wake phrase.
 */
class AssistWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AssistActivity.EXTRA_FROM_WIDGET, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_mic)
            views.setOnClickPendingIntent(R.id.widget_mic, open)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}
