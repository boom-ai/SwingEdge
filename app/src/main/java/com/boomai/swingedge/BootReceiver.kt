package com.boomai.swingedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-arms alarms and jobs after reboot, app update, clock changes, and when exact-alarm access changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    Scheduler.ensureAll(context, withSafetyNet = true)
                    Scheduler.enqueueScan(context, "boot")
                }
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                ACTION_EXACT_ALARM_STATE_CHANGED -> {
                    Scheduler.ensureAll(context, withSafetyNet = true)
                }
                else -> Unit
            }
        } catch (t: Throwable) {
            Log.e(App.TAG, "BootReceiver failed", t)
        }
    }

    private companion object {
        const val ACTION_EXACT_ALARM_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
