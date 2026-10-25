package com.ultrapuika.halight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WidgetModelTest {
    private fun light(on: Boolean, pct: Int? = if (on) 50 else null, dimmable: Boolean = true, availability: Availability? = null) =
        LightState("light.kitchen", "Kitchen", availability ?: if (on) Availability.ON else Availability.OFF, pct, dimmable)

    private fun synced(state: LightState) = WidgetModel().onStateFetched(state, 0, 0)

    @Test
    fun fetchedStateIsDisplayed() {
        val d = synced(light(true, 65)).display
        assertTrue(d.isOn)
        assertEquals(65, d.pct)
        assertTrue(d.available)
        assertFalse(d.pending)
    }

    @Test
    fun setBrightness_isOptimisticAndProducesCommand() {
        val m = synced(light(true, 50)).setBrightness(80)
        assertEquals(80, m.display.pct)
        assertTrue(m.display.pending)
        assertEquals(WidgetModel.Command.TurnOn(80, 1), m.pendingCommand())
    }

    @Test
    fun brightnessLowMediumMax() {
        val base = synced(light(true, 50))
        assertEquals(WidgetModel.Command.TurnOn(1, 1), base.setBrightness(1).pendingCommand())
        assertEquals(WidgetModel.Command.TurnOn(1, 1), base.setBrightness(0).pendingCommand(), "0 % from the slider is clamped to 1 %")
        assertEquals(WidgetModel.Command.TurnOn(50, 1), base.setBrightness(50).pendingCommand())
        assertEquals(WidgetModel.Command.TurnOn(100, 1), base.setBrightness(100).pendingCommand())
    }

    @Test
    fun toggleOffKeepsLastBrightnessAndToggleOnRestoresIt() {
        var m = synced(light(true, 65))
        m = m.toggle()
        assertEquals(WidgetModel.Command.TurnOff(1), m.pendingCommand())
        assertFalse(m.display.isOn)
        assertEquals(0, m.display.pct)
        assertEquals(65, m.display.lastOnPct)
        m = m.onCommandSucceeded(1, 100).onStateFetched(light(false), 1, 100 + WidgetModel.SETTLE_MS)
        assertEquals(65, m.lastOnPct, "an off state must not erase the remembered brightness")
        m = m.toggle()
        assertEquals(WidgetModel.Command.TurnOn(65, 2), m.pendingCommand())
        assertEquals(65, m.display.pct)
    }

    @Test
    fun lastBrightnessFollowsUserAndExternalChanges() {
        var m = synced(light(true, 30)).setBrightness(72)
        assertEquals(72, m.lastOnPct)
        m = m.onCommandSucceeded(1, 0).onStateFetched(light(true, 40), 1, WidgetModel.SETTLE_MS + 1)
        assertEquals(40, m.lastOnPct, "an external change while on becomes the new restore value")
    }

    @Test
    fun stepWhenOffTurnsOnFromLastBrightness() {
        val m = synced(light(true, 60)).toggle().onCommandSucceeded(1, 0)
            .onStateFetched(light(false), 1, WidgetModel.SETTLE_MS).stepBrightness(5)
        assertEquals(WidgetModel.Command.TurnOn(65, 2), m.pendingCommand())
    }

    @Test
    fun staleFetchStartedBeforeUserActionIsIgnored() {
        val start = synced(light(true, 20))
        val seqAtFetchStart = start.actionSeq
        val acted = start.setBrightness(90)
        val after = acted.onStateFetched(light(true, 20), seqAtFetchStart, 10)
        assertSame(acted, after)
        assertEquals(90, after.display.pct)
    }

    @Test
    fun fetchWhileCommandUnsentIsIgnored() {
        val acted = synced(light(true, 20)).setBrightness(90)
        assertSame(acted, acted.onStateFetched(light(true, 20), acted.actionSeq, 10))
    }

    @Test
    fun contradictingStateDuringSettleWindowIsIgnoredThenAccepted() {
        val m = synced(light(true, 20)).setBrightness(90).onCommandSucceeded(1, 1_000)
        val early = m.onStateFetched(light(true, 20), 1, 2_000)
        assertEquals(90, early.display.pct, "Zigbee light has not reported yet")
        val late = early.onStateFetched(light(true, 20), 1, 1_000 + WidgetModel.SETTLE_MS)
        assertEquals(20, late.display.pct, "after settling, the real state wins")
        assertFalse(late.display.pending)
    }

    @Test
    fun matchingStateDuringSettleWindowIsAccepted() {
        val m = synced(light(true, 20)).setBrightness(90).onCommandSucceeded(1, 1_000)
            .onStateFetched(light(true, 89), 1, 1_500)
        assertNull(m.desiredOn)
        assertEquals(89, m.display.pct)
    }

    @Test
    fun olderCommandResultDoesNotClearNewerIntent() {
        val m = synced(light(true, 20)).setBrightness(40).setBrightness(70)
        val afterOld = m.onCommandSucceeded(1, 0)
        assertTrue(afterOld.hasUnsentAction)
        assertEquals(WidgetModel.Command.TurnOn(70, 2), afterOld.pendingCommand())
        assertSame(afterOld, afterOld.onCommandFailed(1, HaException(HaException.Kind.TIMEOUT, "t")))
    }

    @Test
    fun failedLatestCommandRevertsToServerStateAndShowsError() {
        val m = synced(light(true, 20)).setBrightness(70)
            .onCommandFailed(1, HaException(HaException.Kind.UNREACHABLE, "offline"))
        assertEquals(20, m.display.pct)
        assertEquals(WidgetModel.Status.ERROR, m.status)
        assertEquals("offline", m.errorMessage)
        assertNull(m.pendingCommand())
    }

    @Test
    fun unauthorizedBecomesAuthError() {
        val m = synced(light(true)).onFetchFailed(HaException(HaException.Kind.UNAUTHORIZED, "revoked"))
        assertEquals(WidgetModel.Status.AUTH_ERROR, m.status)
        assertEquals(50, m.display.pct, "last known state stays visible")
    }

    @Test
    fun successfulFetchClearsError() {
        val m = synced(light(true)).onFetchFailed(HaException(HaException.Kind.TIMEOUT, "t"))
            .onStateFetched(light(true, 10), 0, 0)
        assertEquals(WidgetModel.Status.OK, m.status)
        assertNull(m.errorMessage)
    }

    @Test
    fun nonDimmableLightIgnoresBrightnessButToggles() {
        val m = synced(light(false, dimmable = false))
        assertSame(m, m.setBrightness(40))
        assertEquals(WidgetModel.Command.TurnOn(null, 1), m.toggle().pendingCommand())
    }

    @Test
    fun unavailableLightIsNotAvailable() {
        val d = synced(light(false, availability = Availability.UNAVAILABLE)).display
        assertFalse(d.available)
        assertFalse(d.isOn)
    }

    @Test
    fun abandonedIntentNoLongerBlocksSync() {
        val stuck = synced(light(true, 20)).setBrightness(90)
        assertSame(stuck, stuck.onStateFetched(light(true, 20), stuck.actionSeq, 0))
        val recovered = stuck.abandonPending().onStateFetched(light(true, 20), stuck.actionSeq, 0)
        assertEquals(20, recovered.display.pct)
        assertNull(recovered.pendingCommand())
        val clean = synced(light(true, 20))
        assertSame(clean, clean.abandonPending())
    }

    @Test
    fun onLightWithoutReportedBrightnessFallsBackToLast() {
        val m = WidgetModel(lastOnPct = 33).onStateFetched(light(true, null), 0, 0)
        assertEquals(33, m.display.pct)
    }
}
