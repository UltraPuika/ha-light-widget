package com.ultrapuika.halight.core

import org.json.JSONObject

/** JSON (de)serialisation of [WidgetModel] for SharedPreferences. Contains no credentials. */
object ModelCodec {
    fun encode(m: WidgetModel): String {
        val o = JSONObject()
        m.server?.let { s ->
            o.put(
                "server",
                JSONObject()
                    .put("entityId", s.entityId)
                    .put("name", s.friendlyName)
                    .put("availability", s.availability.name)
                    .put("pct", s.brightnessPct ?: -1)
                    .put("dimmable", s.supportsBrightness),
            )
        }
        m.desiredOn?.let { o.put("desiredOn", it) }
        m.desiredPct?.let { o.put("desiredPct", it) }
        o.put("lastOnPct", m.lastOnPct)
        o.put("actionSeq", m.actionSeq)
        o.put("ackedSeq", m.ackedSeq)
        o.put("settleUntil", m.settleUntil)
        o.put("status", m.status.name)
        m.errorMessage?.let { o.put("error", it) }
        return o.toString()
    }

    fun decode(json: String?): WidgetModel {
        if (json.isNullOrEmpty()) return WidgetModel()
        return try {
            val o = JSONObject(json)
            val server = o.optJSONObject("server")?.let { s ->
                LightState(
                    entityId = s.getString("entityId"),
                    friendlyName = s.getString("name"),
                    availability = enumOr(s.optString("availability"), Availability.UNKNOWN),
                    brightnessPct = s.optInt("pct", -1).takeIf { it >= 0 },
                    supportsBrightness = s.optBoolean("dimmable", true),
                )
            }
            WidgetModel(
                server = server,
                desiredOn = if (o.has("desiredOn")) o.getBoolean("desiredOn") else null,
                desiredPct = if (o.has("desiredPct")) o.getInt("desiredPct") else null,
                lastOnPct = Brightness.clampOnPct(o.optInt("lastOnPct", 100)),
                actionSeq = o.optLong("actionSeq", 0),
                ackedSeq = o.optLong("ackedSeq", 0),
                settleUntil = o.optLong("settleUntil", 0),
                status = enumOr(o.optString("status"), WidgetModel.Status.LOADING),
                errorMessage = if (o.has("error")) o.getString("error") else null,
            )
        } catch (e: Exception) {
            WidgetModel()
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
