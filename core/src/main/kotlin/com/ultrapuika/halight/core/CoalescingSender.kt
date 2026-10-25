package com.ultrapuika.halight.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends only the most recent submitted value, at most one request at a time.
 *
 * - **Debounce:** a value is sent once no newer value arrived for [quietMs].
 * - **Max wait:** during a continuous stream (e.g. dragging a slider), a value is still sent
 *   at least every [maxWaitMs], so the light follows the finger without flooding Home Assistant.
 * - **No overlap:** values submitted while a request is in flight are coalesced and only the
 *   latest one is sent after it completes.
 */
class CoalescingSender<T : Any>(
    private val scope: CoroutineScope,
    private val quietMs: Long,
    private val maxWaitMs: Long,
    private val clock: () -> Long,
    private val send: suspend (T) -> Unit,
) {
    private val lock = Any()
    private var pending: T? = null
    private var lastSubmitAt = 0L
    private var firstPendingAt = 0L
    private var immediate = false
    private var worker: Job? = null

    /** Queues [value]. With [immediate] the quiet period is skipped (e.g. on finger release). */
    fun submit(value: T, immediate: Boolean = false) {
        synchronized(lock) {
            val now = clock()
            if (pending == null) firstPendingAt = now
            pending = value
            lastSubmitAt = now
            if (immediate) this.immediate = true
            if (worker == null) worker = scope.launch { drain() }
        }
    }

    /** True when nothing is waiting to be sent and no request is in flight. */
    val isIdle: Boolean get() = synchronized(lock) { worker == null }

    /** Suspends until every submitted value has been sent (or failed). */
    suspend fun awaitIdle() {
        while (true) {
            val job = synchronized(lock) { worker } ?: return
            job.join()
        }
    }

    private suspend fun drain() {
        try {
            while (true) {
                while (true) {
                    val wait = synchronized(lock) {
                        if (immediate) 0L else {
                            val now = clock()
                            minOf(quietMs - (now - lastSubmitAt), maxWaitMs - (now - firstPendingAt))
                        }
                    }
                    if (wait <= 0) break
                    delay(wait)
                }
                val value = synchronized(lock) {
                    val v = pending
                    pending = null
                    immediate = false
                    v
                } ?: return
                try {
                    send(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The send function reports its own errors; keep draining newer values.
                }
                synchronized(lock) {
                    if (pending == null) return
                }
            }
        } finally {
            synchronized(lock) {
                worker = null
                // A value submitted after the last check but before this block would be stranded.
                if (pending != null) worker = scope.launch { drain() }
            }
        }
    }
}
