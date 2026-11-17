package com.boomai.swingedge.core

/** Headlines for one stock from Google News RSS (free, public). Titles and links only; never throws. */
object News {
    fun url(q: String): String = "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode("$q when:21d", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"

    private fun tag(x: String, t: String): String {
        val a = x.indexOf("<$t"); if (a < 0) return ""
        val b = x.indexOf('>', a); val c = x.indexOf("</$t>", b); if (b < 0 || c < 0) return ""
        return unesc(x.substring(b + 1, c).replace("<![CDATA[", "").replace("]]>", "").trim())
    }
    private fun unesc(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    fun parse(xml: String?, max: Int = 8): List<Map<String, Any?>> {
        if (xml == null || !xml.contains("<item")) return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        try {
            var i = 0
            while (out.size < max) {
                val a = xml.indexOf("<item", i); if (a < 0) break
                val b = xml.indexOf("</item>", a); if (b < 0) break
                val it = xml.substring(a, b)
                var title = tag(it, "title"); val link = tag(it, "link"); val src = tag(it, "source")
                if (src.isNotEmpty() && title.endsWith(" - $src")) title = title.removeSuffix(" - $src")
                val date = tag(it, "pubDate")
                val ms = try { java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (e: Throwable) { 0L }
                if (title.isNotEmpty() && link.startsWith("https://")) out.add(linkedMapOf("title" to title.take(200), "link" to link, "source" to src.take(60), "ts" to ms))
                i = b + 7
            }
        } catch (t: Throwable) {}
        return out
    }
}
