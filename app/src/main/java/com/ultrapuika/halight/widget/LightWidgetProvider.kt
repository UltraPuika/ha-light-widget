package com.ultrapuika.halight.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.ultrapuika.halight.data.WidgetStore
import com.ultrapuika.halight.sync.LightController
import com.ultrapuika.halight.sync.RefreshJobs

class LightWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Draw the cached state immediately, then fetch the real state.
        appWidgetIds.forEach { WidgetRenderer.render(context, it) }
        launchAsync {
            appWidgetIds.forEach { LightController.refresh(context, it) }
            RefreshJobs.updatePeriodic(context)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        WidgetRenderer.render(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetStore.delete(context, it) }
        RefreshJobs.updatePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        RefreshJobs.cancelAll(context)
    }
}
