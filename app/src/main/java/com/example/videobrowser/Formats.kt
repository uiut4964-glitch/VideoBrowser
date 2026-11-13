package com.example.videobrowser

import java.util.Locale

object Formats {
    private val exts = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp", "mp3", "m4a", "aac", "ogg", "wav")

    fun extOf(url: String, mime: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val e = path.substringAfterLast('.', "").lowercase()
        if (e in exts) return e
        return when {
            mime.contains("webm") -> "webm"
            mime == "audio/mpeg" -> "mp3"
            mime.startsWith("audio/") -> "m4a"
            else -> "mp4"
        }
    }

    fun resolve(item: MediaItem, h: Map<String, String>): List<Format> {
        val out = ArrayList<Format>()
        if (item.type == "hls") {
            val text = Net.fetchText(item.url, h)
            if (Hls.isMaster(text)) {
                for (v in Hls.parseMaster(text, item.url).sortedByDescending { it.bandwidth }) {
                    val d = ArrayList<String>()
                    if (v.resolution.isNotEmpty()) d.add(v.resolution)
                    if (v.bandwidth > 0) d.add(String.format(Locale.US, "%.1f Mbps", v.bandwidth / 1_000_000.0))
                    try {
                        val m = Hls.parseMedia(Net.fetchText(v.url, h), v.url)
                        if (m.duration > 0) {
                            d.add(Util.fmtDur(m.duration))
                            if (v.bandwidth > 0) d.add("≈ " + Util.fmtSize((v.bandwidth / 8 * m.duration).toLong()))
                        }
                    } catch (e: Exception) {
                    }
                    val q = if (v.resolution.contains('x')) v.resolution.substringAfter('x') + "p" else "جودة"
                    out.add(Format("$q • HLS", d.joinToString(" • "), v.url, "hls", "ts", true))
                }
            } else {
                val m = Hls.parseMedia(text, item.url)
                if (m.segs.isNotEmpty()) {
                    out.add(
                        Format(
                            "HLS", Util.fmtDur(m.duration) + " • " + m.segs.size + " مقطع",
                            item.url, "hls", if (m.initUrl != null) "mp4" else "ts"
                        )
                    )
                }
            }
        } else {
            val p = Net.probe(item.url, h)
            if (p.mime.startsWith("text/") || p.mime.startsWith("image/") ||
                p.mime.contains("json") || p.mime.contains("html")
            ) return out
            if (p.total in 1L..30000L) return out
            val ext = extOf(item.url, p.mime)
            val q = Regex("(\\d{3,4})[pP](?![a-zA-Z])").find(item.url)?.groupValues?.get(1)
            val d = ArrayList<String>()
            d.add(Util.fmtSize(p.total))
            d.add(Util.host(item.url))
            d.add(if (p.rangeOk) "يدعم التجزئة والاستئناف" else "بدون تجزئة/استئناف")
            out.add(
                Format(
                    (if (q != null) "${q}p • " else "") + ext.uppercase(),
                    d.joinToString(" • "), item.url, "direct", ext
                )
            )
        }
        return out
    }
}
