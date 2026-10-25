package com.ultrapuika.halight.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingSenderTest {
    private fun TestScope.sender(quiet: Long, maxWait: Long, sent: MutableList<Pair<Long, Int>>, sendDelay: Long = 0) =
        CoalescingSender<Int>(this, quiet, maxWait, { testScheduler.currentTime }) { v ->
            delay(sendDelay)
            sent += testScheduler.currentTime to v
        }

    @Test
    fun rapidTapsAreDebouncedIntoOneRequest() = runTest {
        val sent = mutableListOf<Pair<Long, Int>>()
        val s = sender(quiet = 500, maxWait = 5_000, sent = sent)
        listOf(10, 20, 30, 40).forEach { s.submit(it); advanceTimeBy(100) }
        advanceUntilIdle()
        assertEquals(listOf(800L to 40), sent)
    }

    @Test
    fun continuousDragIsThrottledByMaxWait() = runTest {
        val sent = mutableListOf<Pair<Long, Int>>()
        val s = sender(quiet = 250, maxWait = 400, sent = sent)
        for (v in 1..20) { s.submit(v); advanceTimeBy(50) } // 1 s of drag events every 50 ms
        advanceUntilIdle()
        assertTrue(sent.size in 3..4, "sent=$sent")
        assertEquals(20, sent.last().second, "final value is always sent")
        sent.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 250, "spacing $sent") }
    }

    @Test
    fun noOverlappingRequestsAndOnlyLatestAfterInFlight() = runTest {
        val sent = mutableListOf<Pair<Long, Int>>()
        val s = sender(quiet = 100, maxWait = 100, sent = sent, sendDelay = 1_000)
        s.submit(1)
        advanceTimeBy(200) // first request in flight until t=1100
        s.submit(2); s.submit(3); s.submit(4)
        advanceUntilIdle()
        assertEquals(listOf(1, 4), sent.map { it.second })
        assertTrue(sent[1].first >= sent[0].first + 1_000)
    }

    @Test
    fun immediateSkipsQuietPeriod() = runTest {
        val sent = mutableListOf<Pair<Long, Int>>()
        val s = sender(quiet = 500, maxWait = 5_000, sent = sent)
        s.submit(7, immediate = true)
        runCurrent()
        assertEquals(listOf(0L to 7), sent)
    }

    @Test
    fun failingSendDoesNotStopLaterValues() = runTest {
        val sent = mutableListOf<Int>()
        val s = CoalescingSender<Int>(this, 10, 10, { testScheduler.currentTime }) { v ->
            if (v == 1) error("network down")
            sent += v
        }
        s.submit(1)
        advanceUntilIdle()
        s.submit(2)
        advanceUntilIdle()
        assertEquals(listOf(2), sent)
    }

    @Test
    fun isIdleReflectsPendingAndInFlightWork() = runTest {
        val sent = mutableListOf<Pair<Long, Int>>()
        val s = sender(quiet = 100, maxWait = 100, sent = sent, sendDelay = 500)
        assertTrue(s.isIdle)
        s.submit(1)
        assertFalse(s.isIdle, "waiting for quiet period")
        advanceTimeBy(300)
        assertFalse(s.isIdle, "request in flight")
        advanceUntilIdle()
        assertTrue(s.isIdle)
    }

    @Test
    fun awaitIdleWaitsForFinalSend() = runTest {
        val sent = mutableListOf<Pair<Long, Int>>()
        val s = sender(quiet = 300, maxWait = 1_000, sent = sent, sendDelay = 200)
        val done = CompletableDeferred<Long>()
        s.submit(5)
        launch { s.awaitIdle(); done.complete(testScheduler.currentTime) }
        advanceUntilIdle()
        assertEquals(500L, done.await())
        assertEquals(listOf(500L to 5), sent)
    }
}
