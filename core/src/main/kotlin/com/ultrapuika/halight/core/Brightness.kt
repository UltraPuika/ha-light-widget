package com.ultrapuika.halight.core

import kotlin.math.roundToInt

/**
 * Conversions between Home Assistant's brightness scale (0..255, the `brightness`
 * state attribute) and the percentage scale used by the widget and by the
 * `brightness_pct` service parameter.
 */
object Brightness {
    const val MIN_PCT = 1
    const val MAX_PCT = 100
    const val HA_MAX = 255

    /** Clamps a requested "on" brightness to 1..100. 0 % is expressed by turning the light off. */
    fun clampOnPct(pct: Int): Int = pct.coerceIn(MIN_PCT, MAX_PCT)

    /**
     * HA brightness (0..255) to percent. A light that is on with a non-zero brightness never
     * shows 0 %, because 0 % in the widget means "off".
     */
    fun haToPct(haBrightness: Int): Int {
        val b = haBrightness.coerceIn(0, HA_MAX)
        if (b == 0) return 0
        return (b * 100.0 / HA_MAX).roundToInt().coerceIn(MIN_PCT, MAX_PCT)
    }

    /** Percent to HA brightness (0..255). Inverse of [haToPct] for every percentage. */
    fun pctToHa(pct: Int): Int {
        val p = pct.coerceIn(0, MAX_PCT)
        if (p == 0) return 0
        return (p * HA_MAX / 100.0).roundToInt().coerceIn(1, HA_MAX)
    }

    /**
     * Brightness for tap segment [index] (0-based) out of [count] equal segments.
     * The rightmost segment is always 100 %, the leftmost is 100/count %.
     */
    fun segmentToPct(index: Int, count: Int): Int {
        require(count > 0) { "count must be positive" }
        val i = index.coerceIn(0, count - 1)
        return clampOnPct(((i + 1) * 100.0 / count).roundToInt())
    }

    /** Applies a +/- step to [currentPct], staying within 1..100. */
    fun step(currentPct: Int, delta: Int): Int = clampOnPct(currentPct + delta)
}
