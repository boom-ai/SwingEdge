package com.boomai.swingedge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.boomai.swingedge.core.Platform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** Android implementation of the core's [Platform]. No method here may throw. */
class AndroidPlatform(context: Context) : Platform {

    private val ctx: Context = context.applicationContext

    private val threadSeq = AtomicInteger(0)
    private val httpPool: ExecutorService = Executors.newFixedThreadPool(HTTP_THREADS) { r ->
        Thread(r, "swingedge-http-" + threadSeq.incrementAndGet()).apply { isDaemon = true }
    }

    private val fileLocks = ConcurrentHashMap<String, Any>()

    @Volatile
    private var channelsReady = false

    init {
        ensureChannels()
    }

    override fun now(): Long = System.currentTimeMillis()

    // ------------------------------------------------------------------ HTTP

    override fun httpGetAll(urls: List<String>): List<String?> {
        val n = try { urls.size } catch (_: Throwable) { 0 }
        val out = arrayOfNulls<String>(n)
        if (n == 0) return out.asList()
        try {
            val futures = ArrayList<Future<String?>?>(n)
            for (i in 0 until n) {
                val url = urls[i]
                futures.add(
                    try {
                        httpPool.submit(Callable { fetch(url) })
                    } catch (t: Throwable) {
                        null
                    }
                )
            }
            // Overall wall-clock cap for the batch so a stalled network can never hang a scan for long.
            val deadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(maxOf(45_000L, 20_000L + n * 1_000L))
            for (i in 0 until n) {
                val f = futures[i] ?: continue
                try {
                    val left = deadline - System.nanoTime()
                    out[i] = if (left > 0) f.get(left, TimeUnit.NANOSECONDS) else if (f.isDone) f.get() else null
                    if (!f.isDone) f.cancel(true)
                } catch (t: Throwable) {
                    try { f.cancel(true) } catch (_: Throwable) {}
                    out[i] = null
                }
            }
        } catch (t: Throwable) {
            safeLog("httpGetAll failed: $t")
        }
        return out.asList()
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var ok = false
        try {
            val started = System.nanoTime()
            val u = URL(url)
            val scheme = u.protocol?.lowercase()
            if (scheme != "https" && scheme != "http") return null
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            input = conn.inputStream
            if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                input = GZIPInputStream(input)
            }
            val buf = ByteArray(16 * 1024)
            val bos = ByteArrayOutputStream(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                bos.write(buf, 0, r)
                if (bos.size() > MAX_RESPONSE_BYTES) return null
                if (System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(FETCH_BUDGET_MS)) return null
                if (Thread.currentThread().isInterrupted) return null
            }
            ok = true
            return String(bos.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            return null
        } finally {
            try { input?.close() } catch (_: Throwable) {}
            // Keep the connection alive for reuse on success; drop it on any failure.
            if (!ok) try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ----------------------------------------------------------------- files

    private fun safeName(name: String): String {
        var s = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        if (s.length > 100) s = s.substring(s.length - 100).trimStart('.')
        return if (s.isEmpty()) "_" else s
    }

    private fun lockFor(name: String): Any = fileLocks.getOrPut(name) { Any() }

    override fun read(name: String): String? {
        return try {
            val safe = safeName(name)
            synchronized(lockFor(safe)) {
                val f = File(ctx.filesDir, safe)
                if (!f.isFile) null else f.readText(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            safeLog("read($name) failed: $t")
            null
        }
    }

    override fun write(name: String, text: String) {
        try {
            val safe = safeName(name)
            synchronized(lockFor(safe)) {
                val dir = ctx.filesDir
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, safe)
                val tmp = File(dir, "$safe.tmp")
                FileOutputStream(tmp).use { fos ->
                    fos.write(text.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    try { fos.fd.sync() } catch (_: Throwable) {}
                }
                if (!tmp.renameTo(target)) {
                    // rename(2) normally replaces atomically; this is only a last resort.
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        safeLog("write($name): rename failed")
                    }
                }
            }
        } catch (t: Throwable) {
            safeLog("write($name) failed: $t")
        }
    }

    override fun asset(name: String): String? {
        return try {
            val clean = name.trim().trimStart('/')
            if (clean.isEmpty() || clean.contains("..")) return null
            ctx.assets.open(clean).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (t: Throwable) {
            null
        }
    }

    override fun log(msg: String) {
        safeLog(msg)
    }

    private fun safeLog(msg: String) {
        try { Log.i(App.TAG, msg) } catch (_: Throwable) {}
    }

    // --------------------------------------------------------- notifications

    private fun ensureChannels() {
        if (channelsReady) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val buy = NotificationChannel(CH_BUY, "Buy ideas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New swing BUY ideas"
                enableVibration(true)
            }
            val sell = NotificationChannel(CH_SELL, "Sell / exit calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Exit, stop-loss and target follow-ups"
                enableVibration(true)
            }
            val brief = NotificationChannel(CH_BRIEF, "Daily brief", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Market brief and summaries"
            }
            nm.createNotificationChannels(listOf(buy, sell, brief))
            channelsReady = true
        } catch (t: Throwable) {
            safeLog("ensureChannels failed: $t")
        }
    }

    override fun notify(channel: String, id: Int, title: String, body: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannels()
            val ch = when (channel) {
                "buy" -> CH_BUY
                "sell" -> CH_SELL
                else -> CH_BRIEF
            }
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_stat_swing)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setColor(0xFF22C55E.toInt())
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        } catch (t: Throwable) {
            safeLog("notify failed: $t")
        }
    }

    companion object {
        const val CH_BUY = "buy"
        const val CH_SELL = "sell"
        const val CH_BRIEF = "brief"

        private const val HTTP_THREADS = 8
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        /** Hard wall-clock budget for one request (connect + slow-drip body). */
        private const val FETCH_BUDGET_MS = 30_000L
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }
}
