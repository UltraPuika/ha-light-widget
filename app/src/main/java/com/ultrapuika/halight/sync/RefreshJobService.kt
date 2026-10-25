package com.ultrapuika.halight.sync

import android.app.job.JobParameters
import android.app.job.JobService
import com.ultrapuika.halight.core.WidgetModel
import com.ultrapuika.halight.data.WidgetStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RefreshJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = LightController.scope.launch {
            val now = System.currentTimeMillis()
            val periodic = params.jobId == RefreshJobs.PERIODIC_JOB_ID
            var stillFailing = false
            for (id in WidgetStore.placedWidgetIds(this@RefreshJobService)) {
                val config = WidgetStore.config(this@RefreshJobService, id) ?: continue
                if (periodic) {
                    if (config.refreshMinutes <= 0) continue
                    // Allow some slack so a job that fires slightly early still refreshes.
                    val due = WidgetStore.lastRefresh(this@RefreshJobService, id) + config.refreshMinutes * 60_000L * 3 / 4
                    if (now < due) continue
                }
                LightController.refresh(this@RefreshJobService, id)
                if (WidgetStore.model(this@RefreshJobService, id).status == WidgetModel.Status.ERROR) stillFailing = true
            }
            jobFinished(params, !periodic && stillFailing)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return params.jobId == RefreshJobs.RETRY_JOB_ID
    }
}
