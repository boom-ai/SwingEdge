package com.boomai.swingedge

import android.app.Application
import android.content.Context
import android.util.Log
import com.boomai.swingedge.core.SwingApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Process-wide holder of the single core instance and its Android platform. */
class App : Application() {

    lateinit var platform: AndroidPlatform
        private set

    /** Created on first use (may read files); every caller is wrapped in try/catch. */
    val swing: SwingApp by lazy { SwingApp(platform) }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        platform = AndroidPlatform(this)
        try {
            // Cheap and idempotent: makes sure the 15-minute backup job exists whenever the process starts.
            Scheduler.ensurePeriodic(this)
        } catch (t: Throwable) {
            Log.e(TAG, "ensurePeriodic at startup failed", t)
        }
    }

    private fun installCrashHandler() {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    Log.e(TAG, "Uncaught exception on ${thread.name}", error)
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
                    pw.println("time: $stamp")
                    pw.println("thread: ${thread.name}")
                    error.printStackTrace(pw)
                    pw.flush()
                    File(filesDir, "last_crash.txt").writeText(sw.toString(), Charsets.UTF_8)
                } catch (_: Throwable) {
                    // never let the crash reporter itself throw
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(10)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "installCrashHandler failed", t)
        }
    }

    companion object {
        const val TAG = "SwingEdge"

        fun from(context: Context): App = context.applicationContext as App
    }
}
