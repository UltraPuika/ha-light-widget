package com.ultrapuika.halight.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.SizeF
import android.widget.RemoteViews
import com.ultrapuika.halight.R
import com.ultrapuika.halight.config.ConfigActivity
import com.ultrapuika.halight.config.QuickSliderActivity
import com.ultrapuika.halight.core.Brightness
import com.ultrapuika.halight.core.WidgetModel
import com.ultrapuika.halight.data.CredentialStore
import com.ultrapuika.halight.data.WidgetConfig
import com.ultrapuika.halight.data.WidgetStore

/**
 * Builds the widget's RemoteViews.
 *
 * App widgets cannot host a draggable SeekBar (RemoteViews has no slider and widgets receive
 * no drag gestures). The brightness bar is therefore a ProgressBar overlaid with equally wide
 * tap targets: tapping anywhere on the bar sets that brightness directly on the home screen.
 * "−"/"+" adjust by 1 % for exact values, and tapping the percentage opens a small popup with a
 * real draggable slider for those who want one.
 */
object WidgetRenderer {
    private const val DISABLED_ALPHA = 0.38f

    private val SEGMENT_IDS = intArrayOf(
        R.id.seg_0, R.id.seg_1, R.id.seg_2, R.id.seg_3, R.id.seg_4,
        R.id.seg_5, R.id.seg_6, R.id.seg_7, R.id.seg_8, R.id.seg_9,
        R.id.seg_10, R.id.seg_11, R.id.seg_12, R.id.seg_13, R.id.seg_14,
        R.id.seg_15, R.id.seg_16, R.id.seg_17, R.id.seg_18, R.id.seg_19,
    )

    private data class Variant(val layout: Int, val segments: Int, val step: Int)

    private val SMALL = Variant(R.layout.widget_small, segments = 0, step = 10)
    private val MEDIUM = Variant(R.layout.widget_medium, segments = 10, step = 1)
    private val LARGE = Variant(R.layout.widget_large, segments = 20, step = 1)

    fun render(context: Context, widgetId: Int) {
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, build(context, widgetId))
    }

    fun build(context: Context, widgetId: Int): RemoteViews {
        val config = WidgetStore.config(context, widgetId)
        val credentialsOk = CredentialStore(context).isConfigured
        val model = WidgetStore.model(context, widgetId)
        // The launcher picks the largest layout that fits the current widget size.
        return RemoteViews(
            mapOf(
                SizeF(110f, 40f) to views(context, widgetId, SMALL, config, credentialsOk, model),
                SizeF(180f, 100f) to views(context, widgetId, MEDIUM, config, credentialsOk, model),
                SizeF(250f, 100f) to views(context, widgetId, LARGE, config, credentialsOk, model),
            )
        )
    }

    private fun views(
        context: Context,
        widgetId: Int,
        variant: Variant,
        config: WidgetConfig?,
        credentialsOk: Boolean,
        model: WidgetModel,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, variant.layout)

        if (config == null || !credentialsOk) {
            rv.setTextViewText(R.id.name, context.getString(R.string.widget_not_configured_title))
            rv.setTextViewText(R.id.status, context.getString(R.string.widget_tap_to_set_up))
            rv.setTextViewText(R.id.pct, context.getString(R.string.pct_unknown))
            rv.setOnClickPendingIntent(R.id.header, configIntent(context, widgetId))
            setControls(rv, variant, widgetId, context, power = false, brightness = false, isOn = false)
            rv.setProgressBar(R.id.progress, 100, 0, false)
            return rv
        }

        val d = model.display
        val known = model.server != null
        val controls = known && d.available
        val dimmable = controls && d.supportsBrightness

        rv.setTextViewText(R.id.name, config.displayName)
        rv.setTextViewText(R.id.pct, if (known && d.available) context.getString(R.string.pct_format, d.pct) else context.getString(R.string.pct_unknown))
        rv.setTextViewText(R.id.status, statusText(context, model, d))
        rv.setProgressBar(R.id.progress, 100, if (dimmable) d.pct else 0, false)
        rv.setInt(R.id.progress, "setSecondaryProgress", if (dimmable && !d.isOn) d.lastOnPct else 0)

        val headerIntent = if (model.status == WidgetModel.Status.AUTH_ERROR) configIntent(context, widgetId)
        else broadcast(context, widgetId, WidgetActionReceiver.ACTION_REFRESH, 0)
        rv.setOnClickPendingIntent(R.id.header, headerIntent)
        rv.setContentDescription(R.id.header, context.getString(R.string.cd_refresh, config.displayName))

        setControls(rv, variant, widgetId, context, power = controls, brightness = dimmable, isOn = d.isOn)
        return rv
    }

    private fun statusText(context: Context, model: WidgetModel, d: WidgetModel.Display): String = when {
        model.status == WidgetModel.Status.AUTH_ERROR -> context.getString(R.string.status_auth_error)
        model.status == WidgetModel.Status.ERROR -> context.getString(R.string.status_error)
        model.server == null || model.status == WidgetModel.Status.LOADING -> context.getString(R.string.status_loading)
        !d.available -> context.getString(R.string.status_unavailable)
        d.pending -> context.getString(if (d.isOn) R.string.status_on_sending else R.string.status_off_sending)
        !d.supportsBrightness -> context.getString(if (d.isOn) R.string.status_on_no_dim else R.string.status_off_no_dim)
        d.isOn -> context.getString(R.string.status_on)
        else -> context.getString(R.string.status_off_restore, d.lastOnPct)
    }

    private fun setControls(
        rv: RemoteViews,
        variant: Variant,
        widgetId: Int,
        context: Context,
        power: Boolean,
        brightness: Boolean,
        isOn: Boolean,
    ) {
        rv.setImageViewResource(R.id.power, if (isOn) R.drawable.ic_power_on else R.drawable.ic_power_off)
        rv.setInt(R.id.power, "setBackgroundResource", if (isOn) R.drawable.power_bg_on else R.drawable.power_bg_off)
        rv.setContentDescription(R.id.power, context.getString(if (isOn) R.string.cd_turn_off else R.string.cd_turn_on))
        enable(rv, R.id.power, power, broadcast(context, widgetId, WidgetActionReceiver.ACTION_TOGGLE, 0))

        enable(rv, R.id.minus, brightness, broadcast(context, widgetId, WidgetActionReceiver.ACTION_STEP, -variant.step))
        enable(rv, R.id.plus, brightness, broadcast(context, widgetId, WidgetActionReceiver.ACTION_STEP, variant.step))
        rv.setContentDescription(R.id.minus, context.getString(R.string.cd_dimmer, variant.step))
        rv.setContentDescription(R.id.plus, context.getString(R.string.cd_brighter, variant.step))

        enable(rv, R.id.pct, brightness, if (brightness) sliderIntent(context, widgetId) else null)

        if (variant.segments > 0) {
            rv.setFloat(R.id.progress, "setAlpha", if (brightness) 1f else DISABLED_ALPHA)
            for (i in 0 until variant.segments) {
                val pct = Brightness.segmentToPct(i, variant.segments)
                val id = SEGMENT_IDS[i]
                rv.setOnClickPendingIntent(id, if (brightness) broadcast(context, widgetId, WidgetActionReceiver.ACTION_SET, pct) else null)
                rv.setBoolean(id, "setEnabled", brightness)
                rv.setContentDescription(id, context.getString(R.string.cd_set_brightness, pct))
            }
        }
    }

    private fun enable(rv: RemoteViews, viewId: Int, enabled: Boolean, intent: PendingIntent?) {
        rv.setBoolean(viewId, "setEnabled", enabled)
        rv.setFloat(viewId, "setAlpha", if (enabled) 1f else DISABLED_ALPHA)
        rv.setOnClickPendingIntent(viewId, if (enabled) intent else null)
    }

    private fun broadcast(context: Context, widgetId: Int, action: String, value: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java)
            .setAction(action)
            // A distinct data URI keeps every (widget, action, value) PendingIntent separate.
            .setData(Uri.parse("halight://widget/$widgetId/${action.substringAfterLast('.')}/$value"))
            .putExtra(WidgetActionReceiver.EXTRA_WIDGET_ID, widgetId)
            .putExtra(WidgetActionReceiver.EXTRA_VALUE, value)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun configIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, ConfigActivity::class.java)
            .setData(Uri.parse("halight://config/$widgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun sliderIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, QuickSliderActivity::class.java)
            .setData(Uri.parse("halight://slider/$widgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
