package com.ultrapuika.halight.sync

import android.content.Context
import android.os.SystemClock
import com.ultrapuika.halight.core.CoalescingSender
import com.ultrapuika.halight.core.HaException
import com.ultrapuika.halight.core.WidgetModel
import com.ultrapuika.halight.data.CredentialStore
import com.ultrapuika.halight.data.WidgetStore
import com.ultrapuika.halight.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects user actions, Home Assistant and the widget UI.
 *
 * All state lives in [WidgetStore]; this object only holds short-lived, in-process machinery:
 * one [CoalescingSender] per widget (debounce + single request in flight) and one pending
 * confirmation refresh per widget.
 */
object LightController {
    /** Quiet period after the last tap / slider movement before a command is sent. */
    private const val QUIET_MS = 350L
    /** While the user keeps adjusting, still send at least this often. */
    private const val MAX_WAIT_MS = 600L
    /** First state check after a command; Zigbee lights usually report back within ~1 s. */
    private const val CONFIRM_DELAY_MS = 1_200L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val senders = HashMap<Int, CoalescingSender<WidgetModel.Command>>()
    private val confirmJobs = HashMap<Int, Job>()

    private fun now() = System.currentTimeMillis()

    /**
     * Applies a user action locally (optimistic UI) and queues the resulting command.
     * Returns the updated model.
     */
    fun onUserAction(
        context: Context,
        widgetId: Int,
        immediate: Boolean,
        render: Boolean = true,
        transform: (WidgetModel) -> WidgetModel,
    ): WidgetModel {
        val app = context.applicationContext
        val model = WidgetStore.update(app, widgetId, transform)
        if (render) WidgetRenderer.render(app, widgetId)
        model.pendingCommand()?.let { cmd ->
            synchronized(confirmJobs) { confirmJobs.remove(widgetId) }?.cancel()
            senderFor(app, widgetId).submit(cmd, immediate)
        }
        return model
    }

    /** Suspends until queued commands and their confirmation refreshes for [widgetId] are done. */
    suspend fun awaitIdle(widgetId: Int) {
        repeat(3) {
            synchronized(senders) { senders[widgetId] }?.awaitIdle()
            synchronized(confirmJobs) { confirmJobs[widgetId] }?.join()
        }
    }

    /** Reads the light state from Home Assistant and updates the widget. */
    suspend fun refresh(context: Context, widgetId: Int, showLoading: Boolean = false) {
        val app = context.applicationContext
        val config = WidgetStore.config(app, widgetId)
        val client = CredentialStore(app).client()
        if (config == null || client == null) {
            WidgetRenderer.render(app, widgetId)
            return
        }
        val sender = synchronized(senders) { senders[widgetId] }
        if (sender == null || sender.isIdle) WidgetStore.update(app, widgetId) { it.abandonPending() }
        val seqAtStart = WidgetStore.model(app, widgetId).actionSeq
        if (showLoading) {
            WidgetStore.update(app, widgetId) { it.loading() }
            WidgetRenderer.render(app, widgetId)
        }
        try {
            val state = withContext(Dispatchers.IO) { client.getLight(config.entityId) }
            WidgetStore.update(app, widgetId) { it.onStateFetched(state, seqAtStart, now()) }
            WidgetStore.setLastRefresh(app, widgetId, now())
        } catch (e: HaException) {
            WidgetStore.update(app, widgetId) { it.onFetchFailed(e) }
            if (e.isTransient()) RefreshJobs.scheduleRetryWhenOnline(app)
        }
        WidgetRenderer.render(app, widgetId)
    }

    suspend fun refreshAll(context: Context, showLoading: Boolean = false) {
        WidgetStore.placedWidgetIds(context).forEach { refresh(context, it, showLoading) }
    }

    private fun senderFor(app: Context, widgetId: Int): CoalescingSender<WidgetModel.Command> =
        synchronized(senders) {
            senders.getOrPut(widgetId) {
                CoalescingSender(scope, QUIET_MS, MAX_WAIT_MS, SystemClock::elapsedRealtime) { cmd ->
                    send(app, widgetId, cmd)
                }
            }
        }

    private suspend fun send(app: Context, widgetId: Int, cmd: WidgetModel.Command) {
        val config = WidgetStore.config(app, widgetId) ?: return
        val client = CredentialStore(app).client()
        if (client == null) {
            WidgetStore.update(app, widgetId) {
                it.onCommandFailed(cmd.seq, HaException(HaException.Kind.INVALID_CONFIG, "Not set up"))
            }
            WidgetRenderer.render(app, widgetId)
            return
        }
        try {
            withContext(Dispatchers.IO) {
                when (cmd) {
                    is WidgetModel.Command.TurnOn -> {
                        val pct = cmd.pct
                        if (pct == null) client.turnOnPlain(config.entityId) else client.turnOn(config.entityId, pct)
                    }
                    is WidgetModel.Command.TurnOff -> client.turnOff(config.entityId)
                }
            }
            val model = WidgetStore.update(app, widgetId) { it.onCommandSucceeded(cmd.seq, now()) }
            WidgetRenderer.render(app, widgetId)
            if (cmd.seq == model.actionSeq) scheduleConfirm(app, widgetId)
        } catch (e: HaException) {
            WidgetStore.update(app, widgetId) { it.onCommandFailed(cmd.seq, e) }
            WidgetRenderer.render(app, widgetId)
            if (e.isTransient()) RefreshJobs.scheduleRetryWhenOnline(app)
        }
    }

    /**
     * Re-reads the state shortly after a command. If the light has not reported the new level
     * yet, checks once more after the settle window, after which the real state wins.
     */
    private fun scheduleConfirm(app: Context, widgetId: Int) {
        val job = scope.launch {
            delay(CONFIRM_DELAY_MS)
            refresh(app, widgetId)
            val model = WidgetStore.model(app, widgetId)
            if (model.desiredOn != null && !model.hasUnsentAction) {
                delay((model.settleUntil - now()).coerceAtLeast(0) + 200)
                refresh(app, widgetId)
            }
        }
        synchronized(confirmJobs) { confirmJobs.put(widgetId, job) }?.cancel()
    }

    private fun HaException.isTransient() =
        kind == HaException.Kind.UNREACHABLE || kind == HaException.Kind.TIMEOUT || kind == HaException.Kind.HTTP
}
