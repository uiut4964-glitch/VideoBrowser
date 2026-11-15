package com.example.videobrowser

import java.io.ByteArrayOutputStream

/** استخراج أبعاد الفيديو (H.264) من مقطع TS أو من ملف التهيئة fMP4 */
object Sps {
    private class Br(val b: ByteArray) {
        var p = 0
        fun bit(): Int {
            if (p >= b.size * 8) throw IllegalStateException("eof")
            val v = (b[p shr 3].toInt() shr (7 - (p and 7))) and 1
            p++
            return v
        }
        fun u(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 1) or bit()
            return v
        }
        fun ue(): Int {
            var z = 0
            while (bit() == 0) {
                z++
                if (z > 31) throw IllegalStateException("bad")
            }
            return if (z == 0) 0 else ((1 shl z) - 1 + u(z))
        }
        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }
    }

    private fun unescape(d: ByteArray, off: Int, len: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var z = 0
        for (i in off until minOf(d.size, off + len)) {
            val x = d[i].toInt() and 0xff
            if (z >= 2 && x == 3) { z = 0; continue }
            o.write(x)
            z = if (x == 0) z + 1 else 0
        }
        return o.toByteArray()
    }

    private fun skipScaling(r: Br, size: Int) {
        var last = 8
        var next = 8
        for (i in 0 until size) {
            if (next != 0) {
                val d = r.se()
                next = (last + d + 256) % 256
            }
            if (next != 0) last = next
        }
    }

    // off = أول بايت من بيانات SPS (بعد رأس NAL)
    private fun parse(d: ByteArray, off: Int): IntArray? {
        try {
            val r = Br(unescape(d, off, 80))
            val profile = r.u(8)
            r.u(8)
            r.u(8)
            r.ue()
            var cf = 1
            val high = intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profile in high) {
                cf = r.ue()
                if (cf == 3) r.u(1)
                r.ue()
                r.ue()
                r.u(1)
                if (r.u(1) == 1) {
                    val n = if (cf != 3) 8 else 12
                    for (i in 0 until n) if (r.u(1) == 1) skipScaling(r, if (i < 6) 16 else 64)
                }
            }
            r.ue()
            val poc = r.ue()
            if (poc == 0) {
                r.ue()
            } else if (poc == 1) {
                r.u(1); r.se(); r.se()
                val n = r.ue()
                for (i in 0 until n) r.se()
            }
            r.ue()
            r.u(1)
            val wMbs = r.ue() + 1
            val hMap = r.ue() + 1
            val fmo = r.u(1)
            if (fmo == 0) r.u(1)
            r.u(1)
            var cl = 0; var cr = 0; var ct = 0; var cb = 0
            if (r.u(1) == 1) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue() }
            val cux = if (cf == 0 || cf == 3) 1 else 2
            val cuy = (if (cf == 1) 2 else 1) * (2 - fmo)
            val w = wMbs * 16 - (cl + cr) * cux
            val h = (2 - fmo) * hMap * 16 - (ct + cb) * cuy
            if (w < 64 || h < 64 || w > 8192 || h > 8192) return null
            return intArrayOf(w, h)
        } catch (e: Exception) {
            return null
        }
    }

    private fun find(d: ByteArray): IntArray? {
        var i = 0
        while (i + 4 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte() && (d[i + 3].toInt() and 0x1f) == 7) {
                val r = parse(d, i + 4)
                if (r != null) return r
            }
            i++
        }
        return null
    }

    fun fromTs(d: ByteArray): IntArray? {
        var pid = -1
        var i = 0
        while (i + 188 <= d.size) {
            if (d[i] == 0x47.toByte()) {
                val pusi = (d[i + 1].toInt() and 0x40) != 0
                val afc = (d[i + 3].toInt() shr 4) and 3
                var ps = i + 4
                if ((afc and 2) != 0) ps += 1 + (d[i + 4].toInt() and 0xff)
                if (pusi && (afc and 1) != 0 && ps + 4 <= i + 188 &&
                    d[ps] == 0.toByte() && d[ps + 1] == 0.toByte() && d[ps + 2] == 1.toByte() &&
                    (d[ps + 3].toInt() and 0xf0) == 0xe0
                ) {
                    pid = ((d[i + 1].toInt() and 0x1f) shl 8) or (d[i + 2].toInt() and 0xff)
                    break
                }
            }
            i += 188
        }
        if (pid < 0) return find(d)
        val o = ByteArrayOutputStream()
        i = 0
        while (i + 188 <= d.size && o.size() < 600_000) {
            if (d[i] == 0x47.toByte()) {
                val p = ((d[i + 1].toInt() and 0x1f) shl 8) or (d[i + 2].toInt() and 0xff)
                if (p == pid) {
                    val afc = (d[i + 3].toInt() shr 4) and 3
                    var ps = i + 4
                    if ((afc and 2) != 0) ps += 1 + (d[i + 4].toInt() and 0xff)
                    if ((afc and 1) != 0 && ps < i + 188) o.write(d, ps, i + 188 - ps)
                }
            }
            i += 188
        }
        return find(o.toByteArray())
    }

    fun fromAvcC(d: ByteArray): IntArray? {
        var i = 0
        while (i + 12 < d.size) {
            if (d[i] == 0x61.toByte() && d[i + 1] == 0x76.toByte() && d[i + 2] == 0x63.toByte() && d[i + 3] == 0x43.toByte()) {
                return parse(d, i + 4 + 6 + 2 + 1)
            }
            i++
        }
        return null
    }
}
