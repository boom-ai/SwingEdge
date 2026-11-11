package com.boomai.swingedge

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/** Runs SwingApp.tick off the main thread for both the one-off scan job and the periodic backup job. */
class ScanJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (params == null) return false
        try {
            val trigger = try {
                val t = params.extras?.getString(Scheduler.EXTRA_TRIGGER)
                if (t == "boot") "boot" else "alarm"
            } catch (_: Throwable) {
                "alarm"
            }
            val started = Ticker.run(applicationContext, trigger) {
                try {
                    jobFinished(params, false)
                } catch (t: Throwable) {
                    Log.w(App.TAG, "jobFinished failed: $t")
                }
            }
            // The job holds its own wake lock from here on. (If a direct fallback tick is running, leave its lock alone.)
            if (started) Scheduler.releaseBriefWakeLock()
            // false => another tick is already running (it reschedules the alarm itself); nothing to do here.
            return started
        } catch (t: Throwable) {
            Log.e(App.TAG, "onStartJob failed", t)
            return false
        }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // The OS took the job away (network lost, quota, timeout). A running tick finishes on its own thread
        // if the process survives; make sure an alarm exists either way. No core call here: main thread.
        try {
            Scheduler.scheduleSafetyNet(applicationContext)
            Scheduler.scheduleNextAsync(applicationContext)
        } catch (t: Throwable) {
            Log.e(App.TAG, "onStopJob failed", t)
        }
        return false
    }
}
