package com.boomai.swingedge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the rolling GitHub release `latest` (built by .github/workflows/build.yml).
 * The release title carries the build number ("SwingEdge (build 123)") which equals the APK's versionCode,
 * and every build is signed with the same key, so a newer build installs over the current one.
 *
 *  check()    -> {ok, current, latest, available, sizeMb, notes, at}
 *  download() -> streams the APK into cache, reporting progress
 *  install()  -> PackageInstaller session; Android then shows its own "Update this app?" screen.
 * Nothing here throws; every result is a JSON string for the WebView.
 */
object Updater {
    private const val API = "https://api.github.com/repos/boom-ai/SwingEdge/releases/tags/latest"
    private const val PREFS = "updater"
    @Volatile private var url: String? = null
    @Volatile private var latest = 0
    @Volatile var busy = false; private set

    fun current(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (t: Throwable) { 0 }

    private fun get(u: String, accept: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", accept); c.setRequestProperty("User-Agent", "SwingEdge-Android")
        return c
    }

    fun check(ctx: Context): String {
        val cur = current(ctx)
        return try {
            val c = get(API, "application/vnd.github+json")
            val code = c.responseCode
            if (code != 200) return err("GitHub answered $code. Try again in a few minutes.", cur)
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val name = j.optString("name")
            val build = Regex("build (\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val assets = j.optJSONArray("assets")
            var dl: String? = null; var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { dl = a.optString("browser_download_url"); size = a.optLong("size"); break }
            }
            url = dl; latest = build
            ctx.getSharedPreferences(PREFS, 0).edit().putLong("checkedAt", System.currentTimeMillis()).putInt("latest", build).apply()
            JSONObject().put("ok", true).put("current", cur).put("latest", build).put("available", dl != null && build > cur)
                .put("sizeMb", Math.round(size / 104857.6) / 10.0).put("notes", j.optString("body")).put("at", j.optString("published_at"))
                .put("busy", busy).toString()
        } catch (t: Throwable) {
            Log.w(App.TAG, "update check failed", t)
            err("No connection to GitHub.", cur)
        }
    }

    /** Last check time, so the app checks at most every few hours on its own. */
    fun checkedAt(ctx: Context): Long = ctx.getSharedPreferences(PREFS, 0).getLong("checkedAt", 0L)

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { Log.w(App.TAG, "install permission screen", t) }
    }

    /** Download + install. [progress] gets 0..100. Returns the JSON result for updateInstall. */
    fun downloadAndInstall(ctx: Context, progress: (Int, Long, Long) -> Unit): String {
        if (busy) return JSONObject().put("ok", false).put("error", "Update already downloading").toString()
        if (!canInstall(ctx)) {
            openInstallPermission(ctx)
            return JSONObject().put("ok", false).put("needPermission", true)
                .put("error", "Allow SwingEdge to install updates (switch on, then come back and tap Update again).").toString()
        }
        busy = true
        try {
            if (url == null) check(ctx)
            val u = url ?: return err("No update file found in the latest release.", current(ctx))
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "SwingEdge-$latest.apk")
            val c = get(u, "application/octet-stream")
            if (c.responseCode != 200) return err("Download failed (${c.responseCode}).", current(ctx))
            val total = c.contentLengthLong
            var got = 0L; var lastPct = -1
            c.inputStream.use { inp -> f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = inp.read(buf); if (r < 0) break
                    out.write(buf, 0, r); got += r
                    val pct = if (total > 0) (got * 100 / total).toInt() else -1
                    if (pct != lastPct) { lastPct = pct; progress(pct, got, total) }
                }
            } }
            if (total > 0 && got != total) return err("Download was cut off. Try again.", current(ctx))
            install(ctx, f)
            return JSONObject().put("ok", true).put("installing", true).put("latest", latest).toString()
        } catch (t: Throwable) {
            Log.w(App.TAG, "update download failed", t)
            return err("Download failed: " + (t.message ?: t.javaClass.simpleName), current(ctx))
        } finally { busy = false }
    }

    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(f.length())
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("SwingEdge.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out, 64 * 1024) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java).setPackage(ctx.packageName), flags)
            s.commit(pend.intentSender)
        }
    }

    private fun err(msg: String, cur: Int) = JSONObject().put("ok", false).put("error", msg).put("current", cur).toString()
}

/** Gets the installer's status; when Android needs the user's OK it hands us its confirm screen to show. */
class UpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(App.TAG, "update installed")
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                    Log.w(App.TAG, "update install failed: $msg")
                    android.widget.Toast.makeText(ctx, "Update not installed: $msg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) { Log.e(App.TAG, "UpdateReceiver", t) }
    }
}
