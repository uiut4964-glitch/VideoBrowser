package com.example.videobrowser

import java.io.IOException
import java.util.Locale

object Formats {
    private val exts = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp", "mp3", "m4a", "aac", "ogg", "wav")

    class Info(val duration: Double, val bytes: Long, val w: Int, val h: Int)

    fun isAudio(ext: String): Boolean = ext in setOf("mp3", "m4a", "aac", "ogg", "wav")

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

    /** اسم الجودة بالأسلوب المعتاد: 720P HD ... */
    fun qualityLabel(w: Int, h: Int): String {
        val s = minOf(w, h)
        return when {
            s >= 2000 -> "2160P 4K"
            s >= 1300 -> "1440P 2K"
            s >= 1000 -> "1080P HD"
            s >= 700 -> "720P HD"
            else -> s.toString() + "P"
        }
    }

    private fun fmtEst(bytes: Long): String = if (bytes > 0) "≈ " + Util.fmtSize(bytes) else ""

    /** يقرأ قائمة المقاطع، ويقدّر الحجم من أول مقطع، ويستخرج الأبعاد إن طُلب */
    private fun mediaInfo(url: String, h: Map<String, String>, wantRes: Boolean): Info {
        val m = Hls.parseMedia(Net.fetchText(url, h), url)
        if (m.segs.isEmpty()) throw IOException("empty")
        val s0 = m.segs[0]
        var data = if (s0.rangeStart != null && s0.rangeLength != null)
            Net.readRangeBytes(s0.url, h, s0.rangeStart, s0.rangeLength)
        else Net.readBytes(s0.url, h)
        val size0 = data.size
        var w = 0
        var hh = 0
        if (wantRes) {
            try {
                val k = s0.keyUri
                if (k != null) data = Hls.decrypt(data, Net.readBytes(k, h), s0.iv, s0.seq)
                val init = m.init
                var initData = if (init != null) {
                    if (init.rangeStart != null && init.rangeLength != null)
                        Net.readRangeBytes(init.url, h, init.rangeStart, init.rangeLength)
                    else Net.readBytes(init.url, h)
                } else null
                if (initData != null && init?.keyUri != null) {
                    initData = Hls.decrypt(initData!!, Net.readBytes(init.keyUri, h), init.iv, 0L)
                }
                val r = if (initData != null) Sps.fromAvcC(initData) else Sps.fromTs(data)
                if (r != null) { w = r[0]; hh = r[1] }
            } catch (e: Exception) {
            }
        }
        val bytes = if (s0.dur > 0 && m.duration > 0) (size0 / s0.dur * m.duration).toLong() else -1L
        return Info(m.duration, bytes, w, hh)
    }

    fun resolve(item: MediaItem, h: Map<String, String>): List<Format> {
        val out = ArrayList<Format>()
        if (item.type == "hls") {
            val text = Net.fetchText(item.url, h)
            if (Hls.isMaster(text)) {
                val vars = Hls.parseMaster(text, item.url).sortedWith(
                    compareByDescending<Hls.Variant> { it.resolution.substringAfter('x', "0").toIntOrNull() ?: 0 }
                        .thenByDescending { it.bandwidth }
                )
                for (v in vars) {
                    var dur = 0.0
                    var est = -1L
                    var w = 0
                    var hh = 0
                    val rs = v.resolution.split('x')
                    if (rs.size == 2) { w = rs[0].toIntOrNull() ?: 0; hh = rs[1].toIntOrNull() ?: 0 }
                    try {
                        if (hh > 0) {
                            val m = Hls.parseMedia(Net.fetchText(v.url, h), v.url)
                            dur = m.duration
                            val bps = if (v.avg > 0) v.avg else v.bandwidth
                            if (dur > 0 && bps > 0) est = (bps / 8 * dur).toLong()
                        } else {
                            val inf = mediaInfo(v.url, h, true)
                            dur = inf.duration; est = inf.bytes; w = inf.w; hh = inf.h
                        }
                    } catch (e: Exception) {
                    }
                    val label = if (hh > 0) qualityLabel(w, hh)
                    else "جودة " + String.format(Locale.US, "%.1f Mbps", v.bandwidth / 1_000_000.0)
                    if (est <= 0 && v.bandwidth > 0 && dur > 0) est = (v.bandwidth / 8 * dur).toLong()
                    out.add(Format(label, v.resolution, v.url, "hls", "ts", true, fmtEst(est), false, dur, est))
                }
                for (a in Hls.parseAudio(text, item.url).take(3)) {
                    var est = -1L
                    var dur = 0.0
                    try {
                        val inf = mediaInfo(a.url, h, false)
                        est = inf.bytes; dur = inf.duration
                    } catch (e: Exception) {
                    }
                    out.add(Format("صوت (" + a.name + ")", "", a.url, "hls", "ts", true, fmtEst(est), true, dur))
                }
            } else {
                val inf = mediaInfo(item.url, h, true)
                val m0 = Hls.parseMedia(text, item.url)
                val label = if (inf.h > 0) qualityLabel(inf.w, inf.h)
                else if (inf.bytes > 0 && inf.duration > 0)
                    "جودة " + String.format(Locale.US, "%.1f Mbps", inf.bytes * 8 / inf.duration / 1_000_000.0)
                else "جودة غير معروفة"
                out.add(
                    Format(
                        label, inf.w.toString() + "x" + inf.h, item.url, "hls",
                        if (m0.init != null) "mp4" else "ts", false, fmtEst(inf.bytes), false, inf.duration
                    )
                )
            }
        } else {
            val p = Net.probe(item.url, h)
            if (p.mime.startsWith("text/") || p.mime.startsWith("image/") ||
                p.mime.contains("json") || p.mime.contains("html")
            ) return out
            if (p.total in 1L..30000L) return out
            val ext = extOf(item.url, p.mime)
            val q = Regex("(\\d{3,4})[pP](?![a-zA-Z])").find(item.url)?.groupValues?.get(1)?.toIntOrNull()
            val audio = isAudio(ext)
            val label = if (audio) ext.uppercase()
            else if (q != null) qualityLabel(q * 16 / 9, q)
            else "الجودة الأصلية"
            val d = ArrayList<String>()
            d.add(Util.host(item.url))
            d.add(if (p.rangeOk) "يدعم التجزئة والاستئناف" else "بدون تجزئة/استئناف")
            out.add(Format(label, d.joinToString(" • "), item.url, "direct", ext, false, Util.fmtSize(p.total), audio, 0.0, p.total))
        }
        return out
    }
}
