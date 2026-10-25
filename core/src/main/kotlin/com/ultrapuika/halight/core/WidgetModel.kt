package com.ultrapuika.halight.core

import kotlin.math.abs

/**
 * Persistent per-widget state and the pure rules that combine user actions with
 * (possibly stale) responses from Home Assistant.
 *
 * Ordering guarantees:
 * - Every user action increments [actionSeq]. Commands carry the sequence number they were
 *   created for; a result for an older sequence never clears a newer intent.
 * - A state fetch remembers the [actionSeq] it started at. If the user acted while the fetch
 *   was in flight, or a command is still unsent, the fetched state is discarded.
 * - After a successful command, fetched states that contradict the user's intent are ignored
 *   for [SETTLE_MS] (Zigbee lights often report the new level a moment later).
 */
data class WidgetModel(
    val server: LightState? = null,
    val desiredOn: Boolean? = null,
    val desiredPct: Int? = null,
    /** Last brightness while on; restored when the light is switched back on from the widget. */
    val lastOnPct: Int = 100,
    val actionSeq: Long = 0,
    val ackedSeq: Long = 0,
    val settleUntil: Long = 0,
    val status: Status = Status.LOADING,
    val errorMessage: String? = null,
) {
    enum class Status { LOADING, OK, ERROR, AUTH_ERROR }

    data class Display(
        val isOn: Boolean,
        /** 0 when off, otherwise 1..100. */
        val pct: Int,
        val lastOnPct: Int,
        val available: Boolean,
        val supportsBrightness: Boolean,
        val pending: Boolean,
    )

    sealed class Command {
        abstract val seq: Long
        data class TurnOn(val pct: Int?, override val seq: Long) : Command()
        data class TurnOff(override val seq: Long) : Command()
    }

    val hasUnsentAction: Boolean get() = actionSeq > ackedSeq

    val display: Display
        get() {
            val supports = server?.supportsBrightness ?: true
            val on = desiredOn ?: server?.isOn ?: false
            val pct = when {
                !on -> 0
                desiredOn == true && desiredPct != null -> desiredPct
                server?.isOn == true && server.brightnessPct != null -> server.brightnessPct.coerceAtLeast(1)
                else -> lastOnPct
            }
            return Display(
                isOn = on,
                pct = pct,
                lastOnPct = lastOnPct,
                available = server?.isAvailable ?: false,
                supportsBrightness = supports,
                pending = desiredOn != null,
            )
        }

    // ---- user actions -------------------------------------------------------------------

    fun setBrightness(pct: Int): WidgetModel {
        if (server?.supportsBrightness == false) return this
        val p = Brightness.clampOnPct(pct)
        return copy(desiredOn = true, desiredPct = p, lastOnPct = p, actionSeq = actionSeq + 1)
    }

    fun stepBrightness(delta: Int): WidgetModel {
        val d = display
        val base = if (d.isOn) d.pct else d.lastOnPct
        return setBrightness(Brightness.step(base, delta))
    }

    fun toggle(): WidgetModel {
        val d = display
        return if (d.isOn) {
            copy(desiredOn = false, desiredPct = null, actionSeq = actionSeq + 1)
        } else {
            copy(
                desiredOn = true,
                desiredPct = if (d.supportsBrightness) lastOnPct else null,
                actionSeq = actionSeq + 1,
            )
        }
    }

    /** The command that brings Home Assistant to the user's latest intent, if any is unsent. */
    fun pendingCommand(): Command? = when {
        !hasUnsentAction -> null
        desiredOn == false -> Command.TurnOff(actionSeq)
        desiredOn == true -> Command.TurnOn(desiredPct, actionSeq)
        else -> null
    }

    // ---- results from Home Assistant ----------------------------------------------------

    fun onCommandSucceeded(seq: Long, now: Long): WidgetModel {
        if (seq < ackedSeq) return this
        val latest = seq == actionSeq
        return copy(
            ackedSeq = maxOf(ackedSeq, seq),
            settleUntil = if (latest) now + SETTLE_MS else settleUntil,
            status = Status.OK,
            errorMessage = null,
        )
    }

    fun onCommandFailed(seq: Long, error: HaException): WidgetModel {
        if (seq != actionSeq) return this // a newer action will be sent anyway
        return copy(desiredOn = null, desiredPct = null, ackedSeq = actionSeq, settleUntil = 0)
            .withError(error)
    }

    fun onStateFetched(state: LightState, seqAtStart: Long, now: Long): WidgetModel {
        if (seqAtStart != actionSeq || hasUnsentAction) return this
        if (desiredOn != null && now < settleUntil && !matchesIntent(state)) return this
        val remembered = if (state.isOn && state.brightnessPct != null && state.brightnessPct > 0) state.brightnessPct else lastOnPct
        return copy(
            server = state,
            desiredOn = null,
            desiredPct = null,
            settleUntil = 0,
            lastOnPct = remembered,
            status = Status.OK,
            errorMessage = null,
        )
    }

    fun onFetchFailed(error: HaException): WidgetModel = withError(error)

    /**
     * Drops an intent whose command can no longer be sent (e.g. the process was killed during the
     * debounce window). Without this, [onStateFetched] would ignore Home Assistant forever.
     */
    fun abandonPending(): WidgetModel =
        if (!hasUnsentAction) this else copy(desiredOn = null, desiredPct = null, ackedSeq = actionSeq, settleUntil = 0)

    fun loading(): WidgetModel = copy(status = Status.LOADING, errorMessage = null)

    private fun withError(error: HaException): WidgetModel = copy(
        status = if (error.kind == HaException.Kind.UNAUTHORIZED) Status.AUTH_ERROR else Status.ERROR,
        errorMessage = error.message,
    )

    private fun matchesIntent(state: LightState): Boolean {
        if (desiredOn != state.isOn) return false
        if (desiredOn == false || desiredPct == null || !state.supportsBrightness) return true
        val actual = state.brightnessPct ?: return false
        return abs(actual - desiredPct) <= 1
    }

    companion object {
        const val SETTLE_MS = 4_000L
    }
}
