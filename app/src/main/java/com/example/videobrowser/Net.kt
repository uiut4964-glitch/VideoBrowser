package com.example.videobrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    class Probe(val total: Long, val rangeOk: Boolean, val mime: String)
    data class ContentRange(val start: Long, val end: Long, val total: Long)

    fun connect(url: String, headers: Map<String, String>, range: String? = null): HttpURLConnection {
        var cur = url
        for (i in 0..8) {
            val c = URL(cur).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (!headers.keys.any { it.equals("Cookie", true) }) {
                val ck = try { CookieManager.getInstance().getCookie(cur) } catch (e: Exception) { null }
                if (ck != null) c.setRequestProperty("Cookie", ck)
            }
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("إعادة توجيه HTTP بلا Location")
                cur = URL(URL(cur), loc).toString()
                continue
            }
            return c
        }
        throw IOException("تجاوز عدد إعادة التوجيه المسموح")
    }

    fun parseContentRange(value: String?): ContentRange? {
        if (value == null) return null
        val m = Regex("(?i)^\\s*bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)\\s*$").matchEntire(value) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        val total = m.groupValues[3].takeIf { it != "*" }?.toLongOrNull() ?: -1L
        if (start < 0L || end < start || (total >= 0L && end >= total)) return null
        return ContentRange(start, end, total)
    }

    fun probe(url: String, headers: Map<String, String>): Probe {
        val c = connect(url, headers, "bytes=0-0")
        try {
            val mime = c.contentType?.substringBefore(';')?.trim() ?: ""
            return when (c.responseCode) {
                206 -> {
                    val cr = parseContentRange(c.getHeaderField("Content-Range"))
                        ?: throw IOException("استجابة الخادم لنطاق البايتات غير صالحة")
                    if (cr.start != 0L || cr.end != 0L || cr.total <= 0L ||
                        (c.contentLengthLong >= 0L && c.contentLengthLong != 1L)) {
                        throw IOException("الخادم أعاد نطاق بايتات غير مطابق لطلب الفحص")
                    }
                    Probe(cr.total, true, mime)
                }
                200 -> Probe(c.contentLengthLong, false, mime)
                else -> throw IOException("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    fun fetchText(url: String, headers: Map<String, String>): String {
        val c = connect(url, headers)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun readBytes(url: String, headers: Map<String, String>): ByteArray = readBytesInternal(url, headers, null, null)

    /** Fetch exactly one HLS byte-range; mismatched or ignored ranges are rejected. */
    fun readRangeBytes(url: String, headers: Map<String, String>, start: Long, length: Long): ByteArray {
        if (start < 0L || length <= 0L || start > Long.MAX_VALUE - length) throw IOException("نطاق تنزيل غير صالح")
        if (length > Int.MAX_VALUE.toLong()) throw IOException("نطاق المقطع أكبر من الحد المدعوم")
        return readBytesInternal(url, headers, start, length)
    }

    private fun readBytesInternal(url: String, headers: Map<String, String>, start: Long?, length: Long?): ByteArray {
        val range = if (start != null && length != null) "bytes=$start-${start + length - 1L}" else null
        val c = connect(url, headers, range)
        try {
            val code = c.responseCode
            if (start == null || length == null) {
                if (code !in 200..299) throw IOException("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            }
            if (code == 206) {
                val cr = parseContentRange(c.getHeaderField("Content-Range"))
                    ?: throw IOException("استجابة BYTERANGE بلا Content-Range صالح")
                val expectedEnd = start + length - 1L
                if (cr.start != start || cr.end != expectedEnd || (cr.total >= 0L && cr.total <= expectedEnd)) {
                    throw IOException("الخادم أعاد نطاقًا مختلفًا عن BYTERANGE المطلوب")
                }
                if (c.contentLengthLong >= 0L && c.contentLengthLong != length) {
                    throw IOException("حجم استجابة BYTERANGE لا يطابق طول المقطع")
                }
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size.toLong() != length) throw IOException("مقطع BYTERANGE غير مكتمل")
                return bytes
            }
            // A server may ignore Range only when the requested range is the whole resource.
            if (code == 200 && start == 0L && c.contentLengthLong == length) {
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size.toLong() != length) throw IOException("مورد BYTERANGE غير مكتمل")
                return bytes
            }
            throw IOException(if (code == 200) "الخادم تجاهل طلب BYTERANGE" else "HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    fun saveBytes(url: String, headers: Map<String, String>, f: File) {
        f.writeBytes(readBytes(url, headers))
    }

    fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
