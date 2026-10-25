package com.ultrapuika.halight.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.ultrapuika.halight.data.WidgetStore

/**
 * Background refresh via JobScheduler: no permanently running service, the system batches
 * the work with other jobs and respects Doze / battery saver.
 */
object RefreshJobs {
    const val PERIODIC_JOB_ID = 1001
    const val RETRY_JOB_ID = 1002

    private const val MIN_PERIOD_MINUTES = 15 // JobScheduler minimum

    private fun scheduler(context: Context) = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    private fun component(context: Context) = ComponentName(context, RefreshJobService::class.java)

    /** (Re)schedules the periodic job using the shortest interval chosen by any placed widget. */
    fun updatePeriodic(context: Context) {
        val minutes = WidgetStore.placedWidgetIds(context).asList()
            .mapNotNull { WidgetStore.config(context, it)?.refreshMinutes }
            .filter { it > 0 }
            .minOrNull()
        val scheduler = scheduler(context)
        if (minutes == null) {
            scheduler.cancel(PERIODIC_JOB_ID)
            return
        }
        val periodMs = minutes.coerceAtLeast(MIN_PERIOD_MINUTES) * 60_000L
        val existing = scheduler.getPendingJob(PERIODIC_JOB_ID)
        if (existing != null && existing.intervalMillis == periodMs) return
        scheduler.schedule(
            JobInfo.Builder(PERIODIC_JOB_ID, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(periodMs, periodMs / 3)
                .setPersisted(true)
                .build()
        )
    }

    /** Refreshes all widgets as soon as a network is available again (exponential backoff). */
    fun scheduleRetryWhenOnline(context: Context) {
        val scheduler = scheduler(context)
        if (scheduler.getPendingJob(RETRY_JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(RETRY_JOB_ID, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(30_000)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
        )
    }

    fun cancelAll(context: Context) {
        scheduler(context).cancel(PERIODIC_JOB_ID)
        scheduler(context).cancel(RETRY_JOB_ID)
    }
}
