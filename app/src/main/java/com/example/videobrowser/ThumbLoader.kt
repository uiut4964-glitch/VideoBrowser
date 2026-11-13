package com.example.videobrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

object ThumbLoader {
    class Thumb(val bmp: Bitmap?, val durMs: Long)

    private val cache = LruCache<String, Thumb>(80)
    private val failed = HashSet<String>()
    private val inflight = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun thumbFor(t: Task): Thumb? = cache.get(t.id + ":d") ?: cache.get(t.id + ":p")

    private fun scale(b: Bitmap): Bitmap {
        val max = 360
        if (b.width <= max) return b
        val h = (b.height * max.toFloat() / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, max, h, true)
    }

    private fun fromPoster(url: String, headers: Map<String, String>): Bitmap? {
        if (!url.startsWith("http")) return null
        val bytes = Net.readBytes(url, headers)
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return scale(b)
    }

    fun loadUrl(url: String, headers: Map<String, String>, cb: (Bitmap?) -> Unit) {
        pool.execute {
            val b = try { fromPoster(url, headers) } catch (e: Exception) { null }
            main.post { cb(b) }
        }
    }

    // يجب استدعاؤها من الخيط الرئيسي
    fun loadTask(ctx: Context, t: Task, cb: () -> Unit) {
        val done = t.status == St.DONE
        val key = t.id + (if (done) ":d" else ":p")
        if (cache.get(key) != null || failed.contains(key) || !inflight.add(key)) return
        val app = ctx.applicationContext
        pool.execute {
            var th: Thumb? = null
            try {
                th = if (done) fromFile(app, t) else fromRemote(t)
            } catch (e: Exception) {
            }
            val res = th
            main.post {
                inflight.remove(key)
                if (res != null && res.bmp != null) cache.put(key, res) else failed.add(key)
                cb()
            }
        }
    }

    private fun fromFile(ctx: Context, t: Task): Thumb? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(t.savedUri))
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0)
            if (f != null) return Thumb(scale(f), dur)
            val p = try { fromPoster(t.thumbUrl, t.headers) } catch (e: Exception) { null }
            return if (p != null) Thumb(p, dur) else null
        } catch (e: Exception) {
            val p = try { fromPoster(t.thumbUrl, t.headers) } catch (e2: Exception) { null }
            return if (p != null) Thumb(p, 0L) else null
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
    }

    private fun fromRemote(t: Task): Thumb? {
        val p = try { fromPoster(t.thumbUrl, t.headers) } catch (e: Exception) { null }
        if (p != null) return Thumb(p, 0L)
        if (t.kind != "direct") return null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(t.url, HashMap(t.headers))
            val f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return Thumb(scale(f), 0L)
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
    }
}
