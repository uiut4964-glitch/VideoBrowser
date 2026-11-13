package com.example.videobrowser

import android.content.Context
import android.webkit.MimeTypeMap
import java.net.URL
import java.util.Locale

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

object Util {
    fun fmtSize(b: Long): String {
        if (b < 0) return "غير معروف"
        val u = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.1f %s", v, u[i])
    }

    fun fmtSpeed(b: Long): String = fmtSize(b) + "/s"

    fun fmtDur(s: Double): String {
        val t = s.toInt()
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun safeName(s: String): String {
        val r = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().trim('.').take(100)
        return if (r.isEmpty()) "video" else r
    }

    fun host(url: String): String = try { URL(url).host } catch (e: Exception) { url }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
