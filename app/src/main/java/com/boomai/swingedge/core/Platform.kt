package com.boomai.swingedge.core

/** Everything the pure-Kotlin core needs from the outside world. Implemented by the Android shell, and by fakes in tests. */
interface Platform {
    /** Epoch milliseconds. */
    fun now(): Long
    /** Fetch all URLs in parallel. Same order as input; null for any failure. Must never throw. */
    fun httpGetAll(urls: List<String>): List<String?>
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
