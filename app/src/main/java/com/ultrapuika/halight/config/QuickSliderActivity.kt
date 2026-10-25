package com.ultrapuika.halight.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.ultrapuika.halight.R
import com.ultrapuika.halight.core.WidgetModel
import com.ultrapuika.halight.data.WidgetStore
import com.ultrapuika.halight.sync.LightController
import com.ultrapuika.halight.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Small popup over the home screen with a real draggable slider, opened by tapping the
 * percentage on the widget. Changes are throttled by [LightController] while dragging and the
 * final value is sent as soon as the finger is lifted.
 */
class QuickSliderActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var tracking = false

    private lateinit var name: TextView
    private lateinit var pct: TextView
    private lateinit var status: TextView
    private lateinit var slider: SeekBar
    private lateinit var power: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val config = WidgetStore.config(this, widgetId)
        if (config == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_quick_slider)
        setFinishOnTouchOutside(true)
        name = requireViewById(R.id.slider_name)
        pct = requireViewById(R.id.slider_pct)
        status = requireViewById(R.id.slider_status)
        slider = requireViewById(R.id.slider)
        power = requireViewById(R.id.slider_power)
        name.text = config.displayName

        slider.min = 1
        slider.max = 100
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                pct.text = getString(R.string.pct_format, progress)
                LightController.onUserAction(this@QuickSliderActivity, widgetId, immediate = false, render = false) {
                    it.setBrightness(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                tracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                val model = LightController.onUserAction(this@QuickSliderActivity, widgetId, immediate = true) {
                    it.setBrightness(seekBar.progress)
                }
                show(model)
            }
        })
        power.setOnClickListener {
            show(LightController.onUserAction(this, widgetId, immediate = true) { it.toggle() })
        }
        requireViewById<Button>(R.id.slider_done).setOnClickListener { finish() }

        show(WidgetStore.model(this, widgetId))
        uiScope.launch {
            LightController.refresh(applicationContext, widgetId)
            if (!tracking) show(WidgetStore.model(this@QuickSliderActivity, widgetId))
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetRenderer.render(this, widgetId)
        super.onDestroy()
    }

    private fun show(model: WidgetModel) {
        val d = model.display
        val usable = model.server != null && d.available
        slider.isEnabled = usable && d.supportsBrightness
        slider.progress = if (d.isOn) d.pct else d.lastOnPct
        pct.text = if (usable) getString(R.string.pct_format, d.pct) else getString(R.string.pct_unknown)
        power.isEnabled = usable
        power.text = getString(if (d.isOn) R.string.turn_off else R.string.turn_on)
        status.text = when {
            model.status == WidgetModel.Status.AUTH_ERROR -> getString(R.string.status_auth_error)
            model.status == WidgetModel.Status.ERROR -> model.errorMessage ?: getString(R.string.status_error)
            model.server == null -> getString(R.string.status_loading)
            !d.available -> getString(R.string.status_unavailable)
            !d.supportsBrightness -> getString(R.string.no_brightness_support)
            d.isOn -> getString(R.string.status_on)
            else -> getString(R.string.slider_off_hint)
        }
    }
}
