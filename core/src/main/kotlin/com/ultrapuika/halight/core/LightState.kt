package com.ultrapuika.halight.core

import org.json.JSONArray
import org.json.JSONObject

enum class Availability { ON, OFF, UNAVAILABLE, UNKNOWN }

/** Snapshot of a Home Assistant light entity as returned by `GET /api/states/<entity_id>`. */
data class LightState(
    val entityId: String,
    val friendlyName: String,
    val availability: Availability,
    /** 0..100 when on and reported by HA, null when off or not reported. */
    val brightnessPct: Int?,
    val supportsBrightness: Boolean,
) {
    val isOn: Boolean get() = availability == Availability.ON
    val isAvailable: Boolean get() = availability == Availability.ON || availability == Availability.OFF
}

/** Parsing of Home Assistant REST API JSON payloads. */
object HaJson {
    /** Color modes that imply dimming support (https://developers.home-assistant.io/docs/core/entity/light). */
    private val NON_DIMMABLE_MODES = setOf("onoff", "unknown")

    /** Deprecated `SUPPORT_BRIGHTNESS` feature bit, still reported by some older integrations. */
    private const val LEGACY_SUPPORT_BRIGHTNESS = 1

    fun parseState(json: String): LightState = parseState(JSONObject(json))

    fun parseState(obj: JSONObject): LightState {
        val entityId = obj.getString("entity_id")
        val attrs = obj.optJSONObject("attributes") ?: JSONObject()
        val availability = when (obj.stringOrNull("state")) {
            "on" -> Availability.ON
            "off" -> Availability.OFF
            "unavailable" -> Availability.UNAVAILABLE
            else -> Availability.UNKNOWN
        }
        val haBrightness = if (attrs.isNull("brightness")) null else attrs.optInt("brightness", -1).takeIf { it >= 0 }
        val pct = if (availability == Availability.ON && haBrightness != null) Brightness.haToPct(haBrightness) else null
        return LightState(
            entityId = entityId,
            friendlyName = attrs.stringOrNull("friendly_name")?.takeIf { it.isNotBlank() } ?: entityId,
            availability = availability,
            brightnessPct = pct,
            supportsBrightness = supportsBrightness(attrs, haBrightness != null),
        )
    }

    /** Parses the array returned by `GET /api/states`, keeping only `light.*` entities, sorted by name. */
    fun parseLights(json: String): List<LightState> {
        val arr = JSONArray(json)
        val result = ArrayList<LightState>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.stringOrNull("entity_id") ?: continue
            if (!id.startsWith("light.")) continue
            result += parseState(obj)
        }
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.friendlyName })
    }

    private fun supportsBrightness(attrs: JSONObject, hasBrightnessValue: Boolean): Boolean {
        val modes = attrs.optJSONArray("supported_color_modes")
        if (modes != null && modes.length() > 0) {
            for (i in 0 until modes.length()) {
                if (modes.optString(i) !in NON_DIMMABLE_MODES) return true
            }
            return false
        }
        if (!attrs.isNull("supported_features") &&
            attrs.optInt("supported_features", 0) and LEGACY_SUPPORT_BRIGHTNESS != 0
        ) return true
        return hasBrightnessValue
    }

    /** `optString` differs between Android's and the reference org.json for JSON null; avoid it. */
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else opt(key)?.toString()
}
