package com.boomai.swingedge.core

/** Everything the pure-Kotlin core needs from the outside world. Implemented by the Android shell, and by fakes in tests. */
interface Platform {
    /** Epoch milliseconds. */
    fun now(): Long
    /** Fetch all URLs in parallel. Same order as input; null for any failure. Must never throw. */
    fun httpGetAll(urls: List<String>): List<String?>
    /** POST a JSON body; null on any failure. */
    fun httpPost(url: String, body: String): String? = null
    /**
     * Generic HTTPS request with custom headers (broker APIs). Returns the status code and body for ANY HTTP status
     * (so error JSON can be read), or null when the network failed / the URL is not https. Must never throw.
     * [body] is sent as-is (the caller sets Content-Type in [headers]). Responses larger than [maxBytes] return null.
     */
    fun httpRequest(method: String, url: String, headers: Map<String, String>, body: String?, maxBytes: Int = 1_000_000): HttpResp? = null
    /** Read a small private text file; null if missing. */
    fun read(name: String): String?
    /** Atomically write a small private text file. */
    fun write(name: String, text: String)
    /** Post a phone notification. channel is one of "buy", "sell", "brief". */
    fun notify(channel: String, id: Int, title: String, body: String)
    /** Read a bundled asset as text (e.g. "universe.csv"); null if missing. */
    fun asset(name: String): String?
    fun log(msg: String)
}


/** Status code and body text of an HTTP response. */
class HttpResp(val code: Int, val body: String)

/** Marker for platforms (tests) where background work must run inline so results are deterministic. */
interface SyncPlatform
