package com.ultrapuika.halight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaJsonTest {
    @Test
    fun parsesDimmableLightThatIsOn() {
        val s = HaJson.parseState(
            """{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":166,
               "supported_color_modes":["color_temp","xy"],"color_mode":"xy","friendly_name":"Kitchen"},
               "last_changed":"2026-01-01T00:00:00+00:00"}"""
        )
        assertEquals("light.kitchen", s.entityId)
        assertEquals("Kitchen", s.friendlyName)
        assertEquals(Availability.ON, s.availability)
        assertEquals(65, s.brightnessPct)
        assertTrue(s.supportsBrightness)
    }

    @Test
    fun offLightHasNullBrightnessButIsStillDimmable() {
        val s = HaJson.parseState(
            """{"entity_id":"light.desk","state":"off","attributes":{"brightness":null,
               "supported_color_modes":["brightness"],"friendly_name":"Desk"}}"""
        )
        assertEquals(Availability.OFF, s.availability)
        assertNull(s.brightnessPct)
        assertTrue(s.supportsBrightness)
    }

    @Test
    fun onOffOnlyLightIsNotDimmable() {
        val s = HaJson.parseState(
            """{"entity_id":"light.plug","state":"on","attributes":{"supported_color_modes":["onoff"],"friendly_name":"Plug"}}"""
        )
        assertFalse(s.supportsBrightness)
        assertNull(s.brightnessPct)
    }

    @Test
    fun legacySupportedFeaturesBitIsHonoured() {
        val s = HaJson.parseState("""{"entity_id":"light.old","state":"off","attributes":{"supported_features":1}}""")
        assertTrue(s.supportsBrightness)
        val t = HaJson.parseState("""{"entity_id":"light.old2","state":"off","attributes":{"supported_features":0}}""")
        assertFalse(t.supportsBrightness)
    }

    @Test
    fun unavailableAndUnknownStates() {
        val u = HaJson.parseState("""{"entity_id":"light.a","state":"unavailable","attributes":{"friendly_name":"A"}}""")
        assertEquals(Availability.UNAVAILABLE, u.availability)
        assertFalse(u.isAvailable)
        val k = HaJson.parseState("""{"entity_id":"light.b","state":"unknown","attributes":{}}""")
        assertEquals(Availability.UNKNOWN, k.availability)
        assertEquals("light.b", k.friendlyName, "falls back to entity id without friendly_name")
    }

    @Test
    fun parseLightsFiltersAndSorts() {
        val list = HaJson.parseLights(
            """[{"entity_id":"switch.x","state":"on","attributes":{}},
                {"entity_id":"light.z","state":"off","attributes":{"friendly_name":"zeta"}},
                {"entity_id":"sensor.t","state":"21","attributes":{}},
                {"entity_id":"light.a","state":"on","attributes":{"friendly_name":"Alpha","brightness":255,"supported_color_modes":["brightness"]}}]"""
        )
        assertEquals(listOf("light.a", "light.z"), list.map { it.entityId })
        assertEquals(100, list[0].brightnessPct)
    }
}
