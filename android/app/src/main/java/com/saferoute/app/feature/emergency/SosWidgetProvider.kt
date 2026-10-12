// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.saferoute.app.R
import com.saferoute.app.core.emergency.SosEntryPoint

/**
 * What the launcher draws for the widget: the layout, and what a tap on its button does.
 *
 * REMOTE VIEWS are a description of a small layout that another app (the launcher) draws for
 * us. The app's code is not running while the widget is on screen, so the tap cannot call a
 * function: it carries a PendingIntent, here the same "start, from the widget" that the tile
 * and the notification use ([EmergencyShortcut]). The emergency screen then asks the
 * `StartSos` use case, so the widget shares the consent gate and the five-second countdown.
 */
internal fun buildSosWidgetViews(context: Context): RemoteViews =
    RemoteViews(context.packageName, R.layout.emergency_widget).apply {
        setOnClickPendingIntent(
            R.id.emergency_widget_button,
            EmergencyShortcut.startPendingIntent(context, SosEntryPoint.WIDGET),
        )
    }

/**
 * The home-screen widget "SafeRoute SOS": one button that starts an SOS (P014c1).
 *
 * An APP WIDGET PROVIDER is a broadcast receiver: Android tells it when a widget was put on
 * the home screen, and it answers with the views to draw. This one never changes what it
 * shows, asks for no periodic update, reads nothing and uses no network: it shows fixed
 * text, the same for everyone, and nothing of the account.
 *
 * It starts nothing by itself. Nothing is logged and nothing is stored here.
 */
class SosWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildSosWidgetViews(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }
}
