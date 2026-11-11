package com.boomai.swingedge

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background schedule:
 *  - one AlarmManager alarm (exact + allow-while-idle when permitted) at SwingApp.nextWakeAt()
 *  - the alarm only enqueues ScanJobService (network work happens in the job)
 *  - a persisted 15-minute periodic job as a backup
 */
object Scheduler {
    const val JOB_SCAN = 1001
    const val JOB_PERIODIC = 1002
    const val EXTRA_TRIGGER = "trigger"
    const val ACTION_WAKE = "com.boomai.swingedge.WAKE"

    private const val REQ_ALARM = 1
    const val MIN_AHEAD_MS = 60_000L
    const val MAX_AHEAD_MS = 18L * 60 * 60 * 1000
    const val SAFETY_NET_MS = 15L * 60 * 1000
    private const val PERIOD_MS = 15L * 60 * 1000
    private const val DIRECT_FALLBACK_MS = 10_000L
    private const val DIRECT_TICK_WAKE_MS = 3L * 60 * 1000

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "swingedge-sched").apply { isDaemon = true }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /** Asks the core when to wake next and sets the alarm. May block behind a running tick: never call on the main thread. */
    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        try {
            val now = System.currentTimeMillis()
            val wanted = try {
                App.from(app).swing.nextWakeAt()
            } catch (t: Throwable) {
                Log.e(App.TAG, "nextWakeAt failed", t)
                now + SAFETY_NET_MS
            }
            val at = wanted.coerceIn(now + MIN_AHEAD_MS, now + MAX_AHEAD_MS)
            setAlarm(app, at)
        } catch (t: Throwable) {
            Log.e(App.TAG, "scheduleNext failed", t)
            try { setAlarm(app, System.currentTimeMillis() + SAFETY_NET_MS) } catch (_: Throwable) {}
        }
    }

    /** Main-thread-safe variant of [scheduleNext]. */
    fun scheduleNextAsync(context: Context) {
        val app = context.applicationContext
        try {
            worker.execute { scheduleNext(app) }
        } catch (t: Throwable) {
            Log.e(App.TAG, "scheduleNextAsync failed", t)
        }
    }

    /** Alarm 15 minutes ahead that does not need the core. Replaced by the real one after each tick. */
    fun scheduleSafetyNet(context: Context) {
        try {
            setAlarm(context.applicationContext, System.currentTimeMillis() + SAFETY_NET_MS)
        } catch (t: Throwable) {
            Log.e(App.TAG, "scheduleSafetyNet failed", t)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).setAction(ACTION_WAKE)
        return PendingIntent.getBroadcast(
            context, REQ_ALARM, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** There is exactly one alarm (one PendingIntent); setting it again replaces the previous time. */
    private fun setAlarm(context: Context, atMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context)
        val exactAllowed = try {
            Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        } catch (t: Throwable) {
            false
        }
        if (exactAllowed) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                return
            } catch (t: Throwable) {
                // e.g. SecurityException if the permission was revoked in between
                Log.w(App.TAG, "exact alarm refused, using inexact: $t")
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    /** One-off scan job, expedited where the OS allows it. Does nothing while a tick is already running. */
    fun enqueueScan(context: Context, trigger: String) {
        val app = context.applicationContext
        try {
            if (Ticker.isBusy()) return // scheduling the same job id would stop the running job
            val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val comp = ComponentName(app, ScanJobService::class.java)
            val extras = PersistableBundle().apply { putString(EXTRA_TRIGGER, trigger) }
            var scheduled = false
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    // Expedited jobs may not carry a deadline/latency; they start as soon as the network is there.
                    val info = JobInfo.Builder(JOB_SCAN, comp)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setExtras(extras)
                        .setExpedited(true)
                        .build()
                    scheduled = js.schedule(info) == JobScheduler.RESULT_SUCCESS
                } catch (t: Throwable) {
                    Log.w(App.TAG, "expedited job refused: $t")
                }
            }
            if (!scheduled) {
                val info = JobInfo.Builder(JOB_SCAN, comp)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(extras)
                    .setMinimumLatency(0L) // no deadline: without a network the job waits; the safety-net alarm retries
                    .build()
                val r = js.schedule(info)
                if (r != JobScheduler.RESULT_SUCCESS) Log.w(App.TAG, "scan job schedule result $r")
            }
        } catch (t: Throwable) {
            Log.e(App.TAG, "enqueueScan failed", t)
        }
    }

    /** Backup: persisted periodic job every 15 minutes. Left alone if already scheduled (so its period is not reset). */
    fun ensurePeriodic(context: Context) {
        val app = context.applicationContext
        try {
            val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(JOB_PERIODIC) != null) return
            val comp = ComponentName(app, ScanJobService::class.java)
            val extras = PersistableBundle().apply { putString(EXTRA_TRIGGER, "alarm") }
            val info = JobInfo.Builder(JOB_PERIODIC, comp)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .setExtras(extras)
                .build()
            val r = js.schedule(info)
            if (r != JobScheduler.RESULT_SUCCESS) Log.w(App.TAG, "periodic job schedule result $r")
        } catch (t: Throwable) {
            Log.e(App.TAG, "ensurePeriodic failed", t)
        }
    }

    /** Everything, safe on the main thread: backup job now, a safety-net alarm now, the precise alarm asynchronously. */
    fun ensureAll(context: Context, withSafetyNet: Boolean) {
        ensurePeriodic(context)
        if (withSafetyNet) scheduleSafetyNet(context)
        scheduleNextAsync(context)
    }

    /**
     * Best-effort fallback for the case where the OS sits on the scan job (deep Doze defers jobs on Android 8-11;
     * Android 12+ uses an expedited job instead). If no tick has started [DIRECT_FALLBACK_MS] after the alarm,
     * run it directly on the tick thread under a wake lock. Ticker guarantees there is never a second concurrent tick.
     */
    fun armDirectFallback(context: Context) {
        val app = context.applicationContext
        try {
            val armedAt = SystemClock.elapsedRealtime()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (Ticker.lastStartAt < armedAt && !Ticker.isBusy()) {
                        holdBriefWakeLock(app, DIRECT_TICK_WAKE_MS)
                        val started = Ticker.run(app, "alarm") {
                            // The pending one-off job is now redundant.
                            try {
                                (app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_SCAN)
                            } catch (_: Throwable) {
                            }
                            releaseBriefWakeLock()
                        }
                        if (!started) releaseBriefWakeLock()
                    }
                } catch (t: Throwable) {
                    Log.e(App.TAG, "direct fallback failed", t)
                }
            }, DIRECT_FALLBACK_MS)
        } catch (t: Throwable) {
            Log.e(App.TAG, "armDirectFallback failed", t)
        }
    }

    /** Keeps the CPU awake for a moment between the alarm broadcast and the job actually starting. */
    @Synchronized
    fun holdBriefWakeLock(context: Context, timeoutMs: Long = 30_000L) {
        try {
            var wl = wakeLock
            if (wl == null) {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SwingEdge:alarm")
                wl.setReferenceCounted(false)
                wakeLock = wl
            }
            wl?.acquire(timeoutMs)
        } catch (t: Throwable) {
            Log.w(App.TAG, "wake lock failed: $t")
        }
    }

    @Synchronized
    fun releaseBriefWakeLock() {
        try {
            val wl = wakeLock
            if (wl != null && wl.isHeld) wl.release()
        } catch (_: Throwable) {
        }
    }
}

/** Runs SwingApp.tick on one background thread; at most one tick is queued/running at any time. */
object Ticker {
    private val running = AtomicBoolean(false)
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "swingedge-tick").apply { isDaemon = true }
    }

    fun isBusy(): Boolean = running.get()

    /** SystemClock.elapsedRealtime() of the most recent tick start; 0 if none in this process. */
    @Volatile
    var lastStartAt: Long = 0L
        private set

    /**
     * @return false if a tick is already in flight (then nothing is started and [onDone] is NOT called).
     * After the tick: the next alarm is scheduled, a foreground MainActivity is told to refresh, then [onDone] runs
     * (on the background thread).
     */
    fun run(context: Context, trigger: String, onDone: (() -> Unit)?): Boolean {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return false
        lastStartAt = SystemClock.elapsedRealtime()
        try {
            exec.execute {
                try {
                    val status = App.from(app).swing.tick(trigger)
                    Log.i(App.TAG, "tick($trigger): $status")
                } catch (t: Throwable) {
                    Log.e(App.TAG, "tick($trigger) failed", t)
                }
                try {
                    Scheduler.scheduleNext(app)
                } catch (t: Throwable) {
                    Log.e(App.TAG, "scheduleNext after tick failed", t)
                }
                running.set(false)
                try {
                    MainActivity.notifyStateChanged()
                } catch (t: Throwable) {
                    Log.e(App.TAG, "notifyStateChanged failed", t)
                }
                try {
                    onDone?.invoke()
                } catch (t: Throwable) {
                    Log.e(App.TAG, "tick onDone failed", t)
                }
            }
            return true
        } catch (t: Throwable) {
            running.set(false)
            Log.e(App.TAG, "Ticker.run failed", t)
            return false
        }
    }
}
