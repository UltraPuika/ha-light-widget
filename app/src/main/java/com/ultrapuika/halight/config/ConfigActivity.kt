package com.ultrapuika.halight.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.ultrapuika.halight.R
import com.ultrapuika.halight.core.Availability
import com.ultrapuika.halight.core.HaClient
import com.ultrapuika.halight.core.HaException
import com.ultrapuika.halight.core.HaUrl
import com.ultrapuika.halight.core.LightState
import com.ultrapuika.halight.data.CredentialStore
import com.ultrapuika.halight.data.WidgetConfig
import com.ultrapuika.halight.data.WidgetStore
import com.ultrapuika.halight.sync.LightController
import com.ultrapuika.halight.sync.RefreshJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connection settings (URL + token) and per-widget settings (light, name, refresh interval).
 *
 * Opened by the launcher when a widget is added or reconfigured, and from the app icon.
 */
class ConfigActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var credentials: CredentialStore

    /** Widget being configured; INVALID when opened from the launcher icon with no widget placed. */
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var launchedForWidget = false
    private var placedIds = IntArray(0)

    private var allLights: List<LightState> = emptyList()
    private var shownLights: List<LightState> = emptyList()
    private var selectedEntity: String? = null
    private var autoName: String? = null
    private var previewJob: Job? = null

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connectionStatus: TextView
    private lateinit var widgetSection: View
    private lateinit var widgetSpinner: Spinner
    private lateinit var lightSection: View
    private lateinit var lightFilter: EditText
    private lateinit var lightSpinner: Spinner
    private lateinit var lightPreview: TextView
    private lateinit var nameInput: EditText
    private lateinit var refreshSpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var saveStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentials = CredentialStore(this)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        launchedForWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (launchedForWidget) {
            // If the user backs out, the launcher removes the half-added widget.
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }

        setContentView(R.layout.activity_config)
        requireViewById<View>(R.id.config_root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        bindViews()
        populate()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        urlInput = requireViewById(R.id.url_input)
        tokenInput = requireViewById(R.id.token_input)
        connectionStatus = requireViewById(R.id.connection_status)
        widgetSection = requireViewById(R.id.widget_section)
        widgetSpinner = requireViewById(R.id.widget_spinner)
        lightSection = requireViewById(R.id.light_section)
        lightFilter = requireViewById(R.id.light_filter)
        lightSpinner = requireViewById(R.id.light_spinner)
        lightPreview = requireViewById(R.id.light_preview)
        nameInput = requireViewById(R.id.name_input)
        refreshSpinner = requireViewById(R.id.refresh_spinner)
        saveButton = requireViewById(R.id.save_button)
        saveStatus = requireViewById(R.id.save_status)

        requireViewById<Button>(R.id.test_button).setOnClickListener { testConnection() }
        requireViewById<Button>(R.id.load_lights_button).setOnClickListener { loadLights() }
        saveButton.setOnClickListener { save() }

        refreshSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            REFRESH_OPTIONS.map { getString(it.second) },
        )

        lightFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showLights()
        })
        lightSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                shownLights.getOrNull(position)?.let(::onLightSelected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun populate() {
        urlInput.setText(credentials.baseUrl.orEmpty())
        tokenInput.hint = getString(if (credentials.hasToken) R.string.token_hint_stored else R.string.token_hint)

        placedIds = WidgetStore.placedWidgetIds(this)
        if (!launchedForWidget && placedIds.isNotEmpty()) {
            widgetSection.visibility = View.VISIBLE
            widgetSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                placedIds.mapIndexed { i, id ->
                    getString(R.string.widget_label, i + 1, WidgetStore.config(this, id)?.displayName ?: getString(R.string.widget_not_configured_title))
                },
            )
            widgetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    widgetId = placedIds[position]
                    loadWidgetConfig()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            widgetId = placedIds[0]
        } else {
            widgetSection.visibility = View.GONE
        }

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            lightSection.visibility = View.GONE
            requireViewById<TextView>(R.id.no_widget_hint).visibility = View.VISIBLE
            saveButton.setText(R.string.save_connection)
        } else {
            loadWidgetConfig()
        }
        if (credentials.isConfigured) loadLights()
    }

    private fun loadWidgetConfig() {
        val config = WidgetStore.config(this, widgetId)
        selectedEntity = config?.entityId
        nameInput.setText(config?.displayName.orEmpty())
        autoName = null
        val idx = REFRESH_OPTIONS.indexOfFirst { it.first == (config?.refreshMinutes ?: DEFAULT_REFRESH) }
        refreshSpinner.setSelection(if (idx >= 0) idx else 0)
        showLights()
    }

    /** Client from the values currently typed in, falling back to the stored token. */
    private fun clientFromInputs(): HaClient? {
        val url = when (val r = HaUrl.normalize(urlInput.text.toString())) {
            is HaUrl.Result.Valid -> r.baseUrl
            is HaUrl.Result.Invalid -> {
                connectionStatus.text = r.reason
                return null
            }
        }
        val token = tokenInput.text.toString().trim().ifEmpty { credentials.token() }
        if (token.isNullOrEmpty()) {
            connectionStatus.text = getString(R.string.token_missing)
            return null
        }
        return HaClient(url, token)
    }

    private fun testConnection() {
        val client = clientFromInputs() ?: return
        connectionStatus.text = getString(R.string.testing)
        uiScope.launch {
            connectionStatus.text = try {
                withContext(Dispatchers.IO) { client.checkConnection() }
                val https = urlInput.text.toString().trim().startsWith("https://", ignoreCase = true)
                getString(if (https) R.string.connection_ok else R.string.connection_ok_http)
            } catch (e: HaException) {
                describe(e)
            }
        }
    }

    private fun loadLights() {
        val client = clientFromInputs() ?: return
        lightPreview.text = getString(R.string.loading_lights)
        uiScope.launch {
            try {
                allLights = withContext(Dispatchers.IO) { client.listLights() }
                connectionStatus.text = getString(R.string.lights_found, allLights.size)
                if (allLights.isEmpty()) lightPreview.text = getString(R.string.no_lights)
                showLights()
            } catch (e: HaException) {
                connectionStatus.text = describe(e)
                lightPreview.text = ""
            }
        }
    }

    private fun showLights() {
        val filter = lightFilter.text.toString().trim()
        shownLights = allLights.filter {
            filter.isEmpty() || it.friendlyName.contains(filter, ignoreCase = true) || it.entityId.contains(filter, ignoreCase = true)
        }
        lightSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            shownLights.map { "${it.friendlyName}  (${it.entityId})" },
        )
        val idx = shownLights.indexOfFirst { it.entityId == selectedEntity }
        if (idx >= 0) lightSpinner.setSelection(idx) else shownLights.firstOrNull()?.let(::onLightSelected)
    }

    private fun onLightSelected(light: LightState) {
        selectedEntity = light.entityId
        val current = nameInput.text.toString()
        if (current.isBlank() || current == autoName) {
            nameInput.setText(light.friendlyName)
            autoName = light.friendlyName
        }
        lightPreview.text = preview(light)
        // Fetch fresh state for the preview (the list may be a few seconds old).
        val client = clientFromInputs() ?: return
        previewJob?.cancel()
        previewJob = uiScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { client.getLight(light.entityId) }
                if (selectedEntity == fresh.entityId) lightPreview.text = preview(fresh)
            } catch (e: HaException) {
                lightPreview.text = describe(e)
            }
        }
    }

    private fun preview(light: LightState): String {
        val state = when (light.availability) {
            Availability.ON -> light.brightnessPct?.let { getString(R.string.preview_on_pct, it) } ?: getString(R.string.status_on)
            Availability.OFF -> getString(R.string.preview_off)
            Availability.UNAVAILABLE -> getString(R.string.status_unavailable)
            Availability.UNKNOWN -> getString(R.string.preview_unknown)
        }
        val dim = getString(if (light.supportsBrightness) R.string.preview_dimmable else R.string.no_brightness_support)
        return getString(R.string.preview_format, light.entityId, state, dim)
    }

    private fun save() {
        val url = when (val r = HaUrl.normalize(urlInput.text.toString())) {
            is HaUrl.Result.Valid -> r.baseUrl
            is HaUrl.Result.Invalid -> {
                saveStatus.text = r.reason
                return
            }
        }
        val newToken = tokenInput.text.toString().trim().ifEmpty { null }
        if (newToken == null && !credentials.hasToken) {
            saveStatus.text = getString(R.string.token_missing)
            return
        }
        val hasWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        val entity = selectedEntity
        if (hasWidget && entity == null) {
            saveStatus.text = getString(R.string.select_light_first)
            return
        }

        credentials.save(url, newToken)
        tokenInput.text.clear()
        tokenInput.hint = getString(R.string.token_hint_stored)

        if (hasWidget && entity != null) {
            val name = nameInput.text.toString().trim().ifEmpty { allLights.firstOrNull { it.entityId == entity }?.friendlyName ?: entity }
            val minutes = REFRESH_OPTIONS[refreshSpinner.selectedItemPosition.coerceIn(0, REFRESH_OPTIONS.lastIndex)].first
            WidgetStore.saveConfig(this, widgetId, WidgetConfig(entity, name, minutes))
        }

        // Configuration changed: refresh every widget (credentials are shared) and reschedule polling.
        val app = applicationContext
        LightController.scope.launch {
            RefreshJobs.updatePeriodic(app)
            LightController.refreshAll(app, showLoading = true)
        }

        if (launchedForWidget) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        } else {
            saveStatus.text = getString(R.string.saved)
        }
    }

    private fun describe(e: HaException): String = when (e.kind) {
        HaException.Kind.UNAUTHORIZED -> getString(R.string.error_unauthorized)
        HaException.Kind.TLS -> getString(R.string.error_tls)
        HaException.Kind.UNREACHABLE, HaException.Kind.TIMEOUT -> getString(R.string.error_unreachable, e.message)
        else -> e.message ?: getString(R.string.status_error)
    }

    private companion object {
        const val DEFAULT_REFRESH = 30
        val REFRESH_OPTIONS = listOf(
            0 to R.string.refresh_manual,
            15 to R.string.refresh_15,
            30 to R.string.refresh_30,
            60 to R.string.refresh_60,
            180 to R.string.refresh_180,
        )
    }
}
