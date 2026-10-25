package com.ultrapuika.halight.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultrapuika.halight.sync.LightController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Handles taps on the widget. Not exported; reached only through this app's PendingIntents. */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val value = intent.getIntExtra(EXTRA_VALUE, 0)
        when (intent.action) {
            ACTION_SET -> LightController.onUserAction(context, widgetId, immediate = false) { it.setBrightness(value) }
            ACTION_STEP -> LightController.onUserAction(context, widgetId, immediate = false) { it.stepBrightness(value) }
            ACTION_TOGGLE -> LightController.onUserAction(context, widgetId, immediate = true) { it.toggle() }
            ACTION_REFRESH -> {
                launchAsync { LightController.refresh(context, widgetId, showLoading = true) }
                return
            }
            else -> return
        }
        // Keep the process alive until the (debounced) command and its confirmation are done.
        launchAsync { LightController.awaitIdle(widgetId) }
    }

    companion object {
        const val ACTION_SET = "com.ultrapuika.halight.action.SET"
        const val ACTION_STEP = "com.ultrapuika.halight.action.STEP"
        const val ACTION_TOGGLE = "com.ultrapuika.halight.action.TOGGLE"
        const val ACTION_REFRESH = "com.ultrapuika.halight.action.REFRESH"
        const val EXTRA_WIDGET_ID = "widget_id"
        const val EXTRA_VALUE = "value"
    }
}

/** Upper bound for work done under [BroadcastReceiver.goAsync]; the system allows roughly 10 s. */
private const val ASYNC_BUDGET_MS = 9_000L

/**
 * Runs [block] while the broadcast is kept pending. Work that outlives the budget continues in
 * [LightController.scope]; the receiver is released either way.
 */
fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    LightController.scope.launch {
        try {
            withTimeoutOrNull(ASYNC_BUDGET_MS) { block() }
        } finally {
            pending.finish()
        }
    }
}
