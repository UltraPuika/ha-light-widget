package com.ultrapuika.halight.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BrightnessTest {
    @Test
    fun haToPct_endpointsAndMidpoint() {
        assertEquals(0, Brightness.haToPct(0))
        assertEquals(100, Brightness.haToPct(255))
        assertEquals(50, Brightness.haToPct(128))
        assertEquals(65, Brightness.haToPct(166))
    }

    @Test
    fun haToPct_lowestNonZeroBrightnessIsAtLeastOnePercent() {
        assertEquals(1, Brightness.haToPct(1))
        assertEquals(1, Brightness.haToPct(2))
    }

    @Test
    fun haToPct_clampsOutOfRange() {
        assertEquals(0, Brightness.haToPct(-5))
        assertEquals(100, Brightness.haToPct(300))
    }

    @Test
    fun pctToHa_roundTripsEveryPercentage() {
        for (pct in 1..100) {
            val ha = Brightness.pctToHa(pct)
            assertEquals(pct, Brightness.haToPct(ha), "pct=$pct ha=$ha")
        }
        assertEquals(0, Brightness.pctToHa(0))
        assertEquals(255, Brightness.pctToHa(100))
        assertEquals(3, Brightness.pctToHa(1))
    }

    @Test
    fun clampOnPct_keepsLightOnRange() {
        assertEquals(1, Brightness.clampOnPct(0))
        assertEquals(1, Brightness.clampOnPct(-20))
        assertEquals(100, Brightness.clampOnPct(140))
        assertEquals(42, Brightness.clampOnPct(42))
    }

    @Test
    fun segments_mapToEvenPercentages() {
        assertEquals((1..10).map { it * 10 }, (0 until 10).map { Brightness.segmentToPct(it, 10) })
        assertEquals(5, Brightness.segmentToPct(0, 20))
        assertEquals(100, Brightness.segmentToPct(19, 20))
        assertEquals(33, Brightness.segmentToPct(0, 3))
        assertEquals(100, Brightness.segmentToPct(99, 10))
    }

    @Test
    fun step_staysInRange() {
        assertEquals(100, Brightness.step(99, 5))
        assertEquals(1, Brightness.step(3, -5))
        assertEquals(51, Brightness.step(50, 1))
    }
}
