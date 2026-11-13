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

    fun connect(url: String, headers: Map<String, String>, range: String? = null): HttpURLConnection {
        var cur = url
        for (i in 0..8) {
            val c = URL(cur).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (!headers.containsKey("Cookie")) {
                val ck = try { CookieManager.getInstance().getCookie(cur) } catch (e: Exception) { null }
                if (ck != null) c.setRequestProperty("Cookie", ck)
            }
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("redirect")
                cur = URL(URL(cur), loc).toString()
                continue
            }
            return c
        }
        throw IOException("too many redirects")
    }

    fun probe(url: String, headers: Map<String, String>): Probe {
        val c = connect(url, headers, "bytes=0-0")
        try {
            val mime = c.contentType?.substringBefore(';')?.trim() ?: ""
            return when (c.responseCode) {
                206 -> {
                    val cr = c.getHeaderField("Content-Range") ?: ""
                    val t = cr.substringAfter('/', "").trim().toLongOrNull() ?: -1L
                    Probe(t, t > 0, mime)
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

    fun readBytes(url: String, headers: Map<String, String>): ByteArray {
        val c = connect(url, headers)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
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
