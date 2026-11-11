package com.boomai.swingedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fired by AlarmManager. Does no network work: hands over to ScanJobService and arms a safety-net alarm. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Scheduler.holdBriefWakeLock(context)
            // If the job never runs (or the process dies) we still wake up again in 15 minutes.
            Scheduler.scheduleSafetyNet(context)
            Scheduler.enqueueScan(context, "alarm")
            Scheduler.ensurePeriodic(context)
            Scheduler.armDirectFallback(context)
        } catch (t: Throwable) {
            Log.e(App.TAG, "AlarmReceiver failed", t)
        }
    }
}
