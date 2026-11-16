package com.example.videobrowser

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * إعادة تعبئة الفيديو (بدون إعادة ترميز) داخل ملف MP4 سليم:
 * - يبني فهرس التقديم والتأخير
 * - يحسب المدة الحقيقية
 * - يصلح قفزات الزمن (انقطاعات الطوابع الزمنية في بث m3u8)
 */
object Remux {
    class Result(val hasVideo: Boolean)

    fun run(input: File, output: File): Result? {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(input.absolutePath)
            val n = ex.trackCount
            if (n == 0) return null
            mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = IntArray(n) { -1 }
            var added = 0
            var hasVideo = false
            for (i in 0 until n) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    try {
                        map[i] = mux.addTrack(f)
                        ex.selectTrack(i)
                        added++
                        if (mime.startsWith("video/")) hasVideo = true
                    } catch (e: Exception) {
                        map[i] = -1
                    }
                }
            }
            if (added == 0) return null
            mux.start()
            started = true

            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val unset = Long.MIN_VALUE
            val lastRaw = LongArray(n) { unset }
            var offset = 0L
            var first = true
            var lastAdj = 0L
            var samples = 0L
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val ti = ex.sampleTrackIndex
                val pts = ex.sampleTime
                if (ti < 0 || map[ti] < 0 || pts < 0) { ex.advance(); continue }
                if (first) {
                    offset = -pts
                    first = false
                } else {
                    val lr = lastRaw[ti]
                    if (lr != unset && (pts < lr - 1_000_000L || pts > lr + 10_000_000L)) {
                        // قفزة في الزمن: نكمل من آخر نقطة بدل القفز
                        offset = lastAdj + 33_000L - pts
                        for (j in 0 until n) lastRaw[j] = unset
                    }
                }
                lastRaw[ti] = pts
                val adj = (pts + offset).coerceAtLeast(0L)
                if (adj > lastAdj) lastAdj = adj
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, adj, flags)
                mux.writeSampleData(map[ti], buf, info)
                samples++
                ex.advance()
            }
            if (samples == 0L) return null
            mux.stop()
            started = false
            return Result(hasVideo)
        } catch (e: Exception) {
            return null
        } finally {
            try { if (started) mux?.stop() } catch (e: Exception) {}
            try { mux?.release() } catch (e: Exception) {}
            try { ex.release() } catch (e: Exception) {}
        }
    }
}
