package com.example.videobrowser

import java.io.IOException
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Hls {
    class Variant(val url: String, val resolution: String, val bandwidth: Long, val avg: Long = 0L)
    class Seg(
        val url: String,
        val keyUri: String?,
        val iv: String?,
        val seq: Long,
        val dur: Double = 0.0,
        val rangeStart: Long? = null,
        val rangeLength: Long? = null,
        val discontinuityBefore: Boolean = false,
        val gap: Boolean = false
    )
    class Init(
        val url: String,
        val rangeStart: Long? = null,
        val rangeLength: Long? = null,
        val keyUri: String? = null,
        val iv: String? = null
    )
    class Audio(val name: String, val url: String)
    class Media(
        val segs: List<Seg>,
        val init: Init?,
        val duration: Double,
        val endList: Boolean,
        val hasDiscontinuity: Boolean
    )

    private data class ByteRange(val start: Long, val length: Long) {
        val endInclusive: Long get() = start + length - 1L
    }

    fun resolve(base: String, ref: String): String = URL(URL(base), ref).toString()

    fun isMaster(text: String): Boolean = text.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    fun parseMaster(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = attributes(l.substringAfter(':'))
                val bw = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                val avg = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                val res = attrs["RESOLUTION"] ?: ""
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) out.add(Variant(resolve(base, lines[j]), res, bw, avg))
                i = j
            }
            i++
        }
        return out
    }

    fun parseAudio(text: String, base: String): List<Audio> {
        val out = ArrayList<Audio>()
        val seen = HashSet<String>()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("#EXT-X-MEDIA:") && attributes(l.substringAfter(':'))["TYPE"] == "AUDIO") {
                val attrs = attributes(l.substringAfter(':'))
                val u = attrs["URI"] ?: continue
                val full = resolve(base, u)
                if (!seen.add(full)) continue
                out.add(Audio(attrs["NAME"] ?: "صوت", full))
            }
        }
        return out
    }

    /**
     * Parse an HLS media playlist. Byte ranges are resolved according to RFC 8216;
     * unsupported encryption and incomplete media gaps fail explicitly instead of
     * silently producing a malformed file.
     */
    fun parseMedia(text: String, base: String): Media {
        val lines = text.lineSequence().map { it.trim() }.toList()
        if (lines.none { it == "#EXTM3U" }) throw IOException("قائمة HLS غير صالحة")
        if (isMaster(text)) throw IOException("هذا رابط قائمة HLS رئيسية؛ اختر الجودة أولاً")

        var seq = 0L
        var keyUri: String? = null
        var iv: String? = null
        var init: Init? = null
        var dur = 0.0
        var total = 0.0
        var pendingRange: String? = null
        var previousRangeUri: String? = null
        var previousRangeEnd = 0L
        var discontinuity = false
        var hasDiscontinuity = false
        var gap = false
        var endList = false
        val segs = ArrayList<Seg>()

        for (l in lines) {
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    seq = l.substringAfter(':').trim().toLongOrNull()
                        ?: throw IOException("قيمة MEDIA-SEQUENCE غير صالحة")
                }
                l.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(l.substringAfter(':'))
                    val uri = attrs["URI"] ?: throw IOException("EXT-X-MAP بدون URI")
                    val full = resolve(base, uri)
                    val br = attrs["BYTERANGE"]?.let { parseMapRange(it) }
                    if (keyUri != null && iv == null) {
                        throw IOException("ملف تهيئة HLS المشفّر AES-128 يتطلب IV صريحًا")
                    }
                    if (keyUri != null && br != null && br.start > 0L) {
                        throw IOException("BYTERANGE لملف تهيئة AES-128 غير مدعوم بأمان بسبب اعتماد تشفير CBC على البايتات السابقة")
                    }
                    val next = Init(full, br?.start, br?.length, keyUri, iv)
                    val previous = init
                    if (previous != null && (previous.url != next.url || previous.rangeStart != next.rangeStart ||
                            previous.rangeLength != next.rangeLength || previous.keyUri != next.keyUri || previous.iv != next.iv)) {
                        throw IOException("قائمة HLS تغيّر ملف التهيئة أثناء البث؛ لا يمكن دمج هذه الصيغة بأمان")
                    }
                    init = next
                }
                l.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(l.substringAfter(':'))
                    val method = attrs["METHOD"] ?: throw IOException("تعليمة EXT-X-KEY غير صالحة")
                    when (method) {
                        "NONE" -> { keyUri = null; iv = null }
                        "AES-128" -> {
                            val keyFormat = attrs["KEYFORMAT"] ?: "identity"
                            if (keyFormat != "identity") throw IOException("تنسيق مفتاح HLS غير مدعوم: $keyFormat")
                            val uri = attrs["URI"] ?: throw IOException("مفتاح AES-128 بدون URI")
                            keyUri = resolve(base, uri)
                            iv = attrs["IV"]?.let {
                                if (!it.startsWith("0x", true)) throw IOException("قيمة IV غير صالحة")
                                it.substring(2).also { hex ->
                                    if (hex.isEmpty() || hex.length > 32 || hex.any { ch -> ch !in '0'..'9' && ch.lowercaseChar() !in 'a'..'f' }) {
                                        throw IOException("قيمة IV غير صالحة")
                                    }
                                }
                            }
                        }
                        else -> throw IOException("تشفير HLS غير مدعوم: $method")
                    }
                }
                l.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = l.substringAfter(':').trim()
                l.startsWith("#EXTINF:") -> {
                    dur = l.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?: throw IOException("مدة مقطع HLS غير صالحة")
                    if (dur < 0.0) throw IOException("مدة مقطع HLS سالبة")
                }
                l == "#EXT-X-DISCONTINUITY" -> {
                    discontinuity = true
                    hasDiscontinuity = true
                }
                l == "#EXT-X-GAP" -> gap = true
                l == "#EXT-X-ENDLIST" -> endList = true
                l.startsWith("#") -> Unit
                else -> {
                    val full = resolve(base, l)
                    val range = pendingRange?.let { parseSegmentRange(it, full, previousRangeUri, previousRangeEnd) }
                    if (range != null && keyUri != null && range.start > 0L) {
                        throw IOException("BYTERANGE المشفّر AES-128 يحتاج سياق CBC السابق؛ هذه الحالة غير مدعومة بأمان")
                    }
                    if (range != null) {
                        previousRangeUri = full
                        previousRangeEnd = range.start + range.length
                    } else {
                        previousRangeUri = null
                        previousRangeEnd = 0L
                    }
                    segs.add(Seg(full, keyUri, iv, seq, dur, range?.start, range?.length, discontinuity, gap))
                    seq++
                    total += dur
                    dur = 0.0
                    pendingRange = null
                    discontinuity = false
                    gap = false
                }
            }
        }
        if (pendingRange != null) throw IOException("نطاق BYTERANGE بلا مقطع")
        if (segs.isEmpty()) return Media(emptyList(), init, total, endList, hasDiscontinuity)
        if (segs.any { it.gap }) throw IOException("قائمة HLS تحتوي على مقاطع GAP مفقودة؛ تم إيقاف الدمج لتجنب ملف ناقص")
        return Media(segs, init, total, endList, hasDiscontinuity)
    }

    private fun parseSegmentRange(raw: String, url: String, previousUrl: String?, previousEnd: Long): ByteRange {
        val p = raw.split('@', limit = 2)
        val length = p[0].toLongOrNull() ?: throw IOException("قيمة BYTERANGE غير صالحة")
        if (length <= 0L) throw IOException("طول BYTERANGE يجب أن يكون أكبر من صفر")
        val start = if (p.size == 2) {
            p[1].toLongOrNull() ?: throw IOException("بداية BYTERANGE غير صالحة")
        } else {
            if (previousUrl != url) throw IOException("BYTERANGE بدون offset يتطلب استمرار نفس مورد المقطع")
            previousEnd
        }
        if (start < 0L || start > Long.MAX_VALUE - length) throw IOException("نطاق BYTERANGE يتجاوز الحد المسموح")
        return ByteRange(start, length)
    }

    private fun parseMapRange(raw: String): ByteRange {
        val p = raw.split('@', limit = 2)
        val length = p[0].toLongOrNull() ?: throw IOException("BYTERANGE في EXT-X-MAP غير صالح")
        val start = if (p.size == 2) p[1].toLongOrNull() else 0L
        if (length <= 0L || start == null || start < 0L || start > Long.MAX_VALUE - length) {
            throw IOException("BYTERANGE في EXT-X-MAP غير صالح")
        }
        return ByteRange(start, length)
    }

    /** Attribute-list parser that does not split commas inside quoted values. */
    private fun attributes(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < raw.length) {
            while (i < raw.length && (raw[i] == ',' || raw[i].isWhitespace())) i++
            val keyStart = i
            while (i < raw.length && raw[i] != '=' && raw[i] != ',') i++
            if (i >= raw.length || raw[i] != '=') break
            val key = raw.substring(keyStart, i).trim().uppercase()
            i++
            val value = if (i < raw.length && raw[i] == '"') {
                i++
                val start = i
                while (i < raw.length && raw[i] != '"') i++
                val v = raw.substring(start, i)
                if (i < raw.length) i++
                v
            } else {
                val start = i
                while (i < raw.length && raw[i] != ',') i++
                raw.substring(start, i).trim()
            }
            if (key.isNotEmpty()) out[key] = value
            while (i < raw.length && raw[i] != ',') i++
        }
        return out
    }

    fun decrypt(data: ByteArray, key: ByteArray, ivHex: String?, seq: Long): ByteArray {
        if (key.size != 16) throw IOException("مفتاح AES-128 يجب أن يكون 16 بايت")
        if (data.isEmpty() || data.size % 16 != 0) throw IOException("بيانات مقطع AES-128 غير مكتملة")
        val iv = ByteArray(16)
        if (ivHex != null) {
            val h = ivHex.padStart(32, '0').takeLast(32)
            for (i in 0 until 16) iv[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            var s = seq
            for (i in 15 downTo 8) {
                iv[i] = (s and 0xff).toByte()
                s = s shr 8
            }
        }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}
