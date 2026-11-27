package com.boomai.swingedge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.boomai.swingedge.core.Clock

/**
 * Live options watch: a foreground service that runs only on weekdays 09:40–15:20 IST and asks the core to check
 * Nifty, Bank Nifty and India VIX every 60 seconds (BUY / SELL alerts come from the core). Stops itself outside the
 * window. Started by the 15-minute alarm and when the app opens. Never throws.
 */
class OptionWatchService : Service() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastOpt = 0L
    /** live whole-market scan: its own thread, so a slow F&O step never delays it (LiveScan.kt; pacing is in the core) */
    private var liveThread: HandlerThread? = null
    private var liveHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
            if (!inWindow(System.currentTimeMillis())) { stopSelf(); return START_NOT_STICKY }
            if (handler == null) {
                val t = HandlerThread("option-watch"); t.start(); thread = t
                handler = Handler(t.looper)
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swingedge:options").apply { setReferenceCounted(false); acquire(6L * 3600 * 1000) }
                } catch (t2: Throwable) { Log.w(App.TAG, "options wakelock", t2) }
                handler?.post(loop)
                val lt = HandlerThread("live-scan"); lt.start(); liveThread = lt
                liveHandler = Handler(lt.looper).also { it.post(liveLoop) }
            }
        } catch (t: Throwable) {
            Log.e(App.TAG, "OptionWatchService start failed", t)
            try { stopSelf() } catch (t2: Throwable) {}
        }
        return START_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (!inWindow(now)) { stopSelf(); return }
            val app = application as App
            val fast = try { app.swing.fnoActive() } catch (t: Throwable) { false }
            try { app.swing.fnoBackground() } catch (t: Throwable) { Log.w(App.TAG, "fno check", t) }
            if (now - lastOpt >= PERIOD_MS - 1000) { lastOpt = now; try { app.swing.optionsQuick() } catch (t: Throwable) { Log.w(App.TAG, "options check", t) }; try { app.swing.fnoLabStep() } catch (t: Throwable) { Log.w(App.TAG, "fno lab", t) } }
            handler?.postDelayed(this, if (fast) FAST_MS else PERIOD_MS)
        }
    }

    private val liveLoop = object : Runnable {
        override fun run() {
            if (!inWindow(System.currentTimeMillis())) return
            val on = try { (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (t: Throwable) { true }
            val wait = try { (application as App).swing.liveScanStep(on) } catch (t: Throwable) { Log.w(App.TAG, "live scan", t); 5_000L }
            liveHandler?.postDelayed(this, wait.coerceIn(250L, 60_000L))
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Options live watch", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("Options live watch")
            .setContentText("Live F&O: calls, tickers and paper trades until 3:20 pm").setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NID, n)
    }

    override fun onDestroy() {
        try { handler?.removeCallbacksAndMessages(null); thread?.quitSafely() } catch (t: Throwable) {}
        try { liveHandler?.removeCallbacksAndMessages(null); liveThread?.quitSafely() } catch (t: Throwable) {}
        liveHandler = null; liveThread = null
        try { if (wake?.isHeld == true) wake?.release() } catch (t: Throwable) {}
        handler = null; thread = null; wake = null
        super.onDestroy()
    }

    // dataSync foreground services are limited to 6 hours a day on Android 15+; 09:40–15:20 is 5 h 40 min.

    companion object {
        const val CH = "options_watch"
        const val NID = 7700
        const val PERIOD_MS = 60_000L
        /** paper F&O positions / orders open: check stops and targets every 10 s */
        const val FAST_MS = 10_000L
        const val START_MIN = 9 * 60 + 16
        const val END_MIN = 15 * 60 + 20

        fun inWindow(nowMs: Long): Boolean {
            val d = Clock.epochDay(nowMs); val m = Clock.minuteOfDay(nowMs)
            return Clock.isWeekday(d) && m in START_MIN until END_MIN
        }

        /** Starts the watch if it is market time. Safe to call often. */
        fun ensure(context: Context) {
            try {
                if (!inWindow(System.currentTimeMillis())) return
                val i = Intent(context, OptionWatchService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) { Log.w(App.TAG, "options watch not started", t) }
        }
    }
}
