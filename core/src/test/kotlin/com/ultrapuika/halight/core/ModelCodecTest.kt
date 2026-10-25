package com.ultrapuika.halight.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelCodecTest {
    @Test
    fun roundTrip() {
        val m = WidgetModel(
            server = LightState("light.kitchen", "Kitchen \"main\"", Availability.ON, 42, true),
            desiredOn = true, desiredPct = 77, lastOnPct = 77, actionSeq = 9, ackedSeq = 8,
            settleUntil = 123_456_789_000L, status = WidgetModel.Status.ERROR, errorMessage = "offline",
        )
        assertEquals(m, ModelCodec.decode(ModelCodec.encode(m)))
        val empty = WidgetModel(server = LightState("light.x", "X", Availability.OFF, null, false))
        assertEquals(empty, ModelCodec.decode(ModelCodec.encode(empty)))
    }

    @Test
    fun corruptOrMissingDataYieldsDefault() {
        assertEquals(WidgetModel(), ModelCodec.decode(null))
        assertEquals(WidgetModel(), ModelCodec.decode("{not json"))
    }
}
