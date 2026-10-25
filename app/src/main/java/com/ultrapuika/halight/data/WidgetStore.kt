package com.ultrapuika.halight.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.ultrapuika.halight.core.ModelCodec
import com.ultrapuika.halight.core.WidgetModel
import com.ultrapuika.halight.widget.LightWidgetProvider

data class WidgetConfig(val entityId: String, val displayName: String, val refreshMinutes: Int)

/** Per-widget configuration and synchronisation state. No credentials are stored here. */
object WidgetStore {
    private const val PREFS = "widgets"
    private val lock = Any()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context, widgetId: Int): WidgetConfig? {
        val p = prefs(context)
        val entity = p.getString("$widgetId.entity", null) ?: return null
        return WidgetConfig(
            entityId = entity,
            displayName = p.getString("$widgetId.name", null) ?: entity,
            refreshMinutes = p.getInt("$widgetId.refresh", 0),
        )
    }

    fun saveConfig(context: Context, widgetId: Int, config: WidgetConfig) {
        synchronized(lock) {
            val p = prefs(context)
            val entityChanged = p.getString("$widgetId.entity", null) != config.entityId
            val editor = p.edit()
                .putString("$widgetId.entity", config.entityId)
                .putString("$widgetId.name", config.displayName)
                .putInt("$widgetId.refresh", config.refreshMinutes)
            if (entityChanged) editor.remove("$widgetId.model")
            editor.apply()
        }
    }

    fun model(context: Context, widgetId: Int): WidgetModel =
        ModelCodec.decode(prefs(context).getString("$widgetId.model", null))

    /** Atomically applies [transform] to the stored model and returns the new value. */
    fun update(context: Context, widgetId: Int, transform: (WidgetModel) -> WidgetModel): WidgetModel {
        synchronized(lock) {
            val old = model(context, widgetId)
            val new = transform(old)
            if (new != old) prefs(context).edit().putString("$widgetId.model", ModelCodec.encode(new)).apply()
            return new
        }
    }

    fun lastRefresh(context: Context, widgetId: Int): Long = prefs(context).getLong("$widgetId.lastRefresh", 0)

    fun setLastRefresh(context: Context, widgetId: Int, time: Long) {
        prefs(context).edit().putLong("$widgetId.lastRefresh", time).apply()
    }

    fun delete(context: Context, widgetId: Int) {
        synchronized(lock) {
            val p = prefs(context)
            val editor = p.edit()
            p.all.keys.filter { it.startsWith("$widgetId.") }.forEach(editor::remove)
            editor.apply()
        }
    }

    /** Widget ids currently placed on the home screen. */
    fun placedWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, LightWidgetProvider::class.java))
}
