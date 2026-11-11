package com.boomai.swingedge

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Thin shell: one full-screen WebView showing assets/ui/index.html plus the `Android` JS bridge. */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var web: WebView? = null

    @Volatile
    private var pageReady = false
    private var rendererDeaths = 0
    private var lastRendererDeath = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Not edge-to-edge: the decor view keeps the page between the (dark) status and navigation bars.
            window.statusBarColor = BG
            window.navigationBarColor = BG
            val frame = FrameLayout(this)
            frame.setBackgroundColor(BG)
            frame.fitsSystemWindows = true
            root = frame
            setContentView(
                frame,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            createWebView()
        } catch (t: Throwable) {
            Log.e(App.TAG, "onCreate failed", t)
        }
    }

    private fun createWebView() {
        try {
            val frame = root ?: return
            pageReady = false
            val w = WebView(this)
            w.setBackgroundColor(BG)
            w.overScrollMode = View.OVER_SCROLL_NEVER
            w.isVerticalScrollBarEnabled = false
            w.isHorizontalScrollBarEnabled = false
            val s = w.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            // file:///android_asset/ stays readable with file access off; nothing else on disk is reachable.
            s.allowFileAccess = false
            s.allowContentAccess = false
            @Suppress("DEPRECATION")
            s.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            s.allowUniversalAccessFromFileURLs = false
            s.setSupportZoom(false)
            s.builtInZoomControls = false
            s.displayZoomControls = false
            s.setSupportMultipleWindows(false)
            s.javaScriptCanOpenWindowsAutomatically = false
            s.mediaPlaybackRequiresUserGesture = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            s.cacheMode = WebSettings.LOAD_DEFAULT
            s.setGeolocationEnabled(false)
            w.webViewClient = Client()
            w.webChromeClient = Chrome()
            w.addJavascriptInterface(Bridge(), "Android")
            frame.addView(
                w,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            web = w
            w.loadUrl(START_URL)
        } catch (t: Throwable) {
            // e.g. the system WebView package is being updated right now
            Log.e(App.TAG, "createWebView failed", t)
        }
    }

    private fun destroyWebView() {
        val w = web ?: return
        web = null
        pageReady = false
        try { root?.removeView(w) } catch (_: Throwable) {}
        try { w.removeJavascriptInterface("Android") } catch (_: Throwable) {}
        try { w.destroy() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        current = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        try {
            if (web == null && root != null) createWebView()
            web?.onResume()
        } catch (t: Throwable) {
            Log.e(App.TAG, "webview resume failed", t)
        }
        try {
            Scheduler.ensurePeriodic(this)
            // Ticker reschedules the alarm and refreshes the page when the tick is done.
            // If a tick is already running, it will do both; still make sure an alarm exists.
            if (!Ticker.run(this, "open", null)) Scheduler.scheduleNextAsync(this)
        } catch (t: Throwable) {
            Log.e(App.TAG, "onResume tick failed", t)
        }
        // Device status (notifications/battery/alarms) may have changed while we were in Settings.
        stateChanged()
    }

    override fun onPause() {
        try { web?.onPause() } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onStop() {
        try {
            if (current?.get() === this) current = null
        } catch (_: Throwable) {
        }
        super.onStop()
    }

    override fun onDestroy() {
        try {
            if (current?.get() === this) current = null
            destroyWebView()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try {
            val w = web
            if (w != null && w.canGoBack()) {
                w.goBack()
                return
            }
        } catch (t: Throwable) {
            Log.e(App.TAG, "onBackPressed failed", t)
        }
        try { finish() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ JS calls

    private fun js(script: String) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                ui.post { js(script) }
                return
            }
            if (!pageReady) return
            web?.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            Log.e(App.TAG, "evaluateJavascript failed", t)
        }
    }

    private fun stateChanged() {
        js(JS_STATE_CHANGED)
    }

    /** onActionResult(name, resultJsonString) followed by onStateChanged(), in that order. */
    private fun deliverResult(name: String, resultJson: String) {
        js(
            "try{window.onActionResult&&window.onActionResult(" + jsString(name) + "," + jsString(resultJson) +
                ");}catch(e){console.error(e);}" + JS_STATE_CHANGED
        )
    }

    private fun runAction(name: String, argsJson: String) {
        try {
            // look-ups must not queue behind a scan/backtest or behind each other's network waits
            val exec = if (name == "search") searchExec else if (name == "stockInfo" || name == "getChart") readExec else actionExec
            exec.execute {
                val result = try {
                    App.from(this).swing.action(name, argsJson)
                } catch (t: Throwable) {
                    Log.e(App.TAG, "action($name) failed", t)
                    errorJson(t.message ?: t.javaClass.simpleName)
                }
                deliverResult(name, result)
            }
        } catch (t: Throwable) {
            Log.e(App.TAG, "runAction($name) failed", t)
            deliverResult(name, errorJson("Could not start the action"))
        }
    }

    // ------------------------------------------------------------ JS bridge

    private inner class Bridge {

        @JavascriptInterface
        fun getState(): String {
            return try {
                App.from(this@MainActivity).swing.stateJson()
            } catch (t: Throwable) {
                Log.e(App.TAG, "getState failed", t)
                "{}"
            }
        }

        @JavascriptInterface
        fun action(name: String?, argsJson: String?) {
            try {
                val n = name ?: return
                val a = if (argsJson.isNullOrBlank() || argsJson == "undefined" || argsJson == "null") "{}" else argsJson
                runAction(n, a)
            } catch (t: Throwable) {
                Log.e(App.TAG, "bridge.action failed", t)
            }
        }

        @JavascriptInterface
        fun pickHoldingsFile() {
            ui.post {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, PICK_MIME_TYPES)
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_PICK_FILE)
                } catch (t: Throwable) {
                    Log.e(App.TAG, "pickHoldingsFile failed", t)
                    deliverResult(ACTION_IMPORT, errorJson("No file picker available on this phone. Paste the holdings text instead."))
                }
            }
        }

        @JavascriptInterface
        fun requestNotifications() {
            ui.post {
                try {
                    val granted = Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        // If the user denied twice, Android shows nothing; onRequestPermissionsResult then opens Settings.
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                    } else if (!notificationsEnabled()) {
                        openNotificationSettings()
                    } else {
                        stateChanged()
                    }
                } catch (t: Throwable) {
                    Log.e(App.TAG, "requestNotifications failed", t)
                    openNotificationSettings()
                }
            }
        }

        @JavascriptInterface
        fun openBatterySettings() {
            ui.post {
                val pkg = Uri.parse("package:$packageName")
                val candidates = ArrayList<Intent>()
                if (!batteryUnrestricted()) {
                    candidates.add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                }
                candidates.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                candidates.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                startFirst(candidates)
            }
        }

        @JavascriptInterface
        fun openExactAlarmSettings() {
            ui.post {
                val pkg = Uri.parse("package:$packageName")
                val candidates = ArrayList<Intent>()
                if (Build.VERSION.SDK_INT >= 31) {
                    candidates.add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                    candidates.add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    candidates.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                    startFirst(candidates)
                } else {
                    // Before Android 12 exact alarms need no special access.
                    stateChanged()
                }
            }
        }

        @JavascriptInterface
        fun deviceStatus(): String {
            return try {
                JSONObject()
                    .put("notifications", notificationsEnabled())
                    .put("batteryUnrestricted", batteryUnrestricted())
                    .put("exactAlarms", exactAlarmsAllowed())
                    .toString()
            } catch (t: Throwable) {
                Log.e(App.TAG, "deviceStatus failed", t)
                "{\"notifications\":false,\"batteryUnrestricted\":false,\"exactAlarms\":false}"
            }
        }
    }

    // ------------------------------------------------------- device helpers

    private fun notificationsEnabled(): Boolean = try {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    } catch (t: Throwable) {
        false
    }

    private fun batteryUnrestricted(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (t: Throwable) {
        false
    }

    private fun exactAlarmsAllowed(): Boolean = try {
        Build.VERSION.SDK_INT < 31 ||
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    } catch (t: Throwable) {
        false
    }

    private fun openNotificationSettings() {
        val pkg = Uri.parse("package:$packageName")
        startFirst(
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            )
        )
    }

    /** Starts the first intent some activity accepts; never throws. */
    private fun startFirst(candidates: List<Intent>): Boolean {
        for (i in candidates) {
            try {
                startActivity(i)
                return true
            } catch (t: Throwable) {
                Log.w(App.TAG, "cannot start ${i.action}: $t")
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        try {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            if (requestCode == REQ_NOTIF && Build.VERSION.SDK_INT >= 33) {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    // Permanently denied: the system dialog can no longer appear, so go to Settings.
                    openNotificationSettings()
                }
            }
        } catch (t: Throwable) {
            Log.e(App.TAG, "onRequestPermissionsResult failed", t)
        }
        stateChanged()
    }

    // ------------------------------------------------------ holdings import

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        try {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode != REQ_PICK_FILE) return
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) {
                deliverResult(ACTION_IMPORT, "{\"ok\":false,\"cancelled\":true,\"error\":\"No file selected\"}")
                return
            }
            actionExec.execute {
                val result = try {
                    val text = readPickedText(uri)
                    val args = JSONObject().put("text", text).toString()
                    App.from(this).swing.action(ACTION_IMPORT, args)
                } catch (e: ImportException) {
                    errorJson(e.message ?: "Could not read the file")
                } catch (t: Throwable) {
                    Log.e(App.TAG, "import failed", t)
                    errorJson("Could not read the file")
                }
                deliverResult(ACTION_IMPORT, result)
            }
        } catch (t: Throwable) {
            Log.e(App.TAG, "onActivityResult failed", t)
            deliverResult(ACTION_IMPORT, errorJson("Could not read the file"))
        }
    }

    private class ImportException(message: String) : Exception(message)

    private fun readPickedText(uri: Uri): String {
        val bytes = (contentResolver.openInputStream(uri) ?: throw ImportException("Could not open the file")).use { ins ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r < 0) break
                bos.write(buf, 0, r)
                if (bos.size() > MAX_IMPORT_BYTES) throw ImportException("File is larger than 2 MB. Export a smaller CSV.")
            }
            bos.toByteArray()
        }
        if (bytes.isEmpty()) throw ImportException("The file is empty")
        fun b(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        // Excel workbooks (.xlsx is a zip, .xls is OLE) are not text.
        if ((b(0) == 0x50 && b(1) == 0x4B) || (b(0) == 0xD0 && b(1) == 0xCF && b(2) == 0x11 && b(3) == 0xE0)) {
            throw ImportException("This is an Excel workbook. Export it as CSV and pick that file.")
        }
        if (b(0) == 0xFF && b(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (b(0) == 0xFE && b(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val off = if (b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) 3 else 0
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, off, bytes.size - off))
                .toString()
        } catch (e: CharacterCodingException) {
            val cs = try { Charset.forName("windows-1252") } catch (_: Throwable) { Charsets.ISO_8859_1 }
            String(bytes, off, bytes.size - off, cs)
        }
        if (text.indexOf('\u0000') >= 0) throw ImportException("This file is not plain text. Export it as CSV and pick that file.")
        return text
    }

    // ------------------------------------------------------ WebView clients

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return try {
                val uri = request?.url ?: return true
                val url = uri.toString()
                if (url.startsWith(ASSET_PREFIX)) return false
                when (uri.scheme?.lowercase()) {
                    "http", "https", "mailto", "tel" -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (t: Throwable) {
                            Log.w(App.TAG, "no app for $url")
                        }
                    }
                    else -> Log.w(App.TAG, "blocked navigation to $url")
                }
                true
            } catch (t: Throwable) {
                Log.e(App.TAG, "shouldOverrideUrlLoading failed", t)
                true
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            try {
                if (view === web) pageReady = true
            } catch (_: Throwable) {
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Returning true keeps the app alive; the dead WebView must be thrown away and replaced.
            try {
                Log.e(App.TAG, "WebView renderer gone (crashed=${detail?.didCrash()})")
                if (view != null && view === web) {
                    destroyWebView()
                } else if (view != null) {
                    try { (view.parent as? ViewGroup)?.removeView(view) } catch (_: Throwable) {}
                    try { view.destroy() } catch (_: Throwable) {}
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastRendererDeath > 60_000L) rendererDeaths = 0
                lastRendererDeath = now
                rendererDeaths++
                // Back off if it keeps dying; onResume recreates it later in any case.
                val delay = if (rendererDeaths <= 3) 300L else 5_000L
                ui.postDelayed({
                    try {
                        if (web == null && !isFinishing && !isDestroyed) createWebView()
                    } catch (t: Throwable) {
                        Log.e(App.TAG, "recreate WebView failed", t)
                    }
                }, delay)
            } catch (t: Throwable) {
                Log.e(App.TAG, "onRenderProcessGone failed", t)
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
            try {
                if (m != null) Log.d(App.TAG, "js: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
            } catch (_: Throwable) {
            }
            return true
        }
    }

    companion object {
        private const val BG = 0xFF0B0F14.toInt()
        private const val ASSET_PREFIX = "file:///android_asset/"
        private const val START_URL = "file:///android_asset/ui/index.html"
        private const val REQ_PICK_FILE = 41
        private const val REQ_NOTIF = 42
        private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        private const val ACTION_IMPORT = "importHoldings"
        private const val JS_STATE_CHANGED =
            "try{window.onStateChanged&&window.onStateChanged();}catch(e){console.error(e);}"

        private val PICK_MIME_TYPES = arrayOf(
            "text/csv",
            "text/comma-separated-values",
            "text/tab-separated-values",
            "text/plain",
            "text/*",
            "application/csv",
            "application/vnd.ms-excel",
            "application/octet-stream"
        )

        /** One action at a time, in the order the page asked for them. Outlives activity recreation. */
        private val actionExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "swingedge-action").apply { isDaemon = true }
        }
        private val searchExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "swingedge-search").apply { isDaemon = true }
        }
        private val readExec: ExecutorService = Executors.newFixedThreadPool(3) { r ->
            Thread(r, "swingedge-read").apply { isDaemon = true }
        }

        @Volatile
        private var current: WeakReference<MainActivity>? = null

        /** Called from background code after a tick; refreshes the page if the activity is on screen. */
        fun notifyStateChanged() {
            try {
                current?.get()?.stateChanged()
            } catch (t: Throwable) {
                Log.e(App.TAG, "notifyStateChanged failed", t)
            }
        }

        private fun errorJson(message: String): String = try {
            JSONObject().put("ok", false).put("error", message).toString()
        } catch (t: Throwable) {
            "{\"ok\":false,\"error\":\"error\"}"
        }

        /** A JavaScript string literal that is safe to embed in evaluateJavascript source. */
        private fun jsString(s: String): String =
            JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }
}
