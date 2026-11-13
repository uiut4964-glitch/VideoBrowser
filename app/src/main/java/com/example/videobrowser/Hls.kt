package com.example.videobrowser

import java.io.IOException
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Hls {
    class Variant(val url: String, val resolution: String, val bandwidth: Long)
    class Seg(val url: String, val keyUri: String?, val iv: String?, val seq: Long)
    class Media(val segs: List<Seg>, val initUrl: String?, val duration: Double)

    fun resolve(base: String, ref: String): String = URL(URL(base), ref).toString()

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("[:,]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val res = Regex("RESOLUTION=(\\d+x\\d+)").find(l)?.groupValues?.get(1) ?: ""
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) out.add(Variant(resolve(base, lines[j]), res, bw))
                i = j
            }
            i++
        }
        return out
    }

    fun parseMedia(text: String, base: String): Media {
        var seq = 0L
        var keyUri: String? = null
        var iv: String? = null
        var initUrl: String? = null
        var dur = 0.0
        var total = 0.0
        val segs = ArrayList<Seg>()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-MAP:") -> {
                    val m = Regex("URI=\"([^\"]+)\"").find(l)
                    if (m != null) initUrl = resolve(base, m.groupValues[1])
                }
                l.startsWith("#EXT-X-KEY:") -> {
                    if (l.contains("METHOD=AES-128")) {
                        val m = Regex("URI=\"([^\"]+)\"").find(l)
                        keyUri = if (m != null) resolve(base, m.groupValues[1]) else null
                        iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)
                    } else if (l.contains("METHOD=NONE")) {
                        keyUri = null
                        iv = null
                    } else {
                        throw IOException("هذا الفيديو محمي بتشفير غير مدعوم")
                    }
                }
                l.startsWith("#EXTINF:") ->
                    dur = l.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                l.isNotEmpty() && !l.startsWith("#") -> {
                    segs.add(Seg(resolve(base, l), keyUri, iv, seq))
                    seq++
                    total += dur
                    dur = 0.0
                }
            }
        }
        return Media(segs, initUrl, total)
    }

    fun decrypt(data: ByteArray, key: ByteArray, ivHex: String?, seq: Long): ByteArray {
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
