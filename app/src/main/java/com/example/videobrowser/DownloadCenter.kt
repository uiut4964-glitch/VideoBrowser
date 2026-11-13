package com.example.videobrowser

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object DownloadCenter {
    lateinit var ctx: Context
    val tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        ctx = c.applicationContext
        load()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }

    // ---------- حفظ واسترجاع القائمة ----------
    private fun file() = File(ctx.filesDir, "tasks.json")

    @Synchronized
    fun save() {
        try {
            val a = JSONArray()
            for (t in tasks) {
                a.put(
                    JSONObject()
                        .put("id", t.id).put("url", t.url).put("name", t.name).put("kind", t.kind)
                        .put("headers", JSONObject(t.headers))
                        .put("total", t.total).put("parts", t.parts).put("status", t.status)
                        .put("error", t.error).put("savedUri", t.savedUri).put("created", t.created)
                        .put("mime", t.mime).put("segCount", t.segCount).put("rangeOk", t.rangeOk)
                )
            }
            file().writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    private fun load() {
        try {
            if (!file().exists()) return
            val a = JSONArray(file().readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val hj = o.getJSONObject("headers")
                val hm = LinkedHashMap<String, String>()
                hj.keys().forEach { k -> hm[k] = hj.getString(k) }
                val t = Task(o.getString("id"), o.getString("url"), o.getString("name"), o.getString("kind"), hm)
                t.total = o.optLong("total", -1)
                t.parts = o.optInt("parts", 0)
                t.status = o.optInt("status", St.PAUSED)
                t.error = o.optString("error", "")
                t.savedUri = o.optString("savedUri", "")
                t.created = o.optLong("created", 0)
                t.mime = o.optString("mime", "video/mp4")
                t.segCount = o.optInt("segCount", 0)
                t.rangeOk = o.optBoolean("rangeOk", false)
                if (t.status == St.QUEUED || t.status == St.DOWNLOADING || t.status == St.MERGING) t.status = St.PAUSED
                if (t.status != St.DONE) scanTemp(t)
                tasks.add(t)
            }
        } catch (e: Exception) {
        }
    }

    private fun scanTemp(t: Task) {
        val dir = Engine.tmpPath(t)
        if (!dir.exists()) return
        var c = 0
        var b = 0L
        val rx = Regex(if (t.kind == "hls") "s\\d+" else "p\\d+")
        dir.listFiles()?.forEach { f -> if (rx.matches(f.name)) { c++; b += f.length() } }
        if (t.kind == "hls") t.segDone.set(c)
        t.downloaded.set(b)
        t.lastBytes = b
    }

    // ---------- التحكم ----------
    fun add(t: Task) {
        t.status = St.QUEUED
        tasks.add(0, t)
        save()
        ensureService()
        startNext()
        changed()
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        } catch (e: Exception) {
        }
    }

    fun startNext() {
        synchronized(this) {
            val max = Prefs.maxConcurrent(ctx)
            var running = tasks.count { it.status == St.DOWNLOADING || it.status == St.MERGING }
            for (t in tasks.reversed()) {
                if (running >= max) break
                if (t.status == St.QUEUED) {
                    t.status = St.DOWNLOADING
                    running++
                    val g = t.gen
                    pool.execute { runTask(t, g) }
                }
            }
        }
        changed()
    }

    fun pause(t: Task) {
        if (t.status == St.MERGING || t.status == St.DONE) return
        t.stop = true
        t.status = St.PAUSED
        save(); changed(); startNext()
    }

    fun resume(t: Task) {
        if (t.status != St.PAUSED && t.status != St.FAILED) return
        t.gen++
        t.stop = false
        t.error = ""
        t.status = St.QUEUED
        save()
        ensureService()
        startNext()
    }

    fun cancel(t: Task) {
        t.stop = true
        t.gen++
        tasks.remove(t)
        save(); changed()
        pool.execute { synchronized(t.lock) { Engine.tmpPath(t).deleteRecursively() } }
        startNext()
    }

    fun deleteFinished(t: Task) {
        tasks.remove(t)
        save(); changed()
        if (t.savedUri.isNotEmpty()) {
            try {
                val u = Uri.parse(t.savedUri)
                if (DocumentsContract.isDocumentUri(ctx, u)) DocumentsContract.deleteDocument(ctx.contentResolver, u)
                else ctx.contentResolver.delete(u, null, null)
            } catch (e: Exception) {
                try { ctx.contentResolver.delete(Uri.parse(t.savedUri), null, null) } catch (e2: Exception) {}
            }
        }
    }

    fun tick() {
        for (t in tasks) {
            if (t.status == St.DOWNLOADING) {
                val b = t.downloaded.get()
                t.speed = (b - t.lastBytes).coerceAtLeast(0)
                t.lastBytes = b
            } else {
                t.speed = 0
            }
        }
        changed()
    }

    private fun runTask(t: Task, g: Int) {
        try {
            synchronized(t.lock) { execute(t, g) }
        } finally {
            save(); changed(); startNext()
        }
    }

    private fun execute(t: Task, g: Int) {
        val alive = { !t.stop && t.gen == g }
        if (!alive()) return
        try {
            if (Prefs.wifiOnly(ctx) && !Net.onWifi(ctx)) throw IOException("لا يوجد اتصال Wi-Fi (الإعداد: Wi-Fi فقط)")
            t.error = ""
            if (t.kind == "hls") Engine.hls(t, alive) else Engine.direct(t, alive)
            if (!alive()) return
            t.status = St.MERGING
            changed()
            Engine.merge(t)
            t.status = St.DONE
        } catch (e: Exception) {
            if (alive()) {
                t.status = St.FAILED
                t.error = e.message ?: e.javaClass.simpleName
            }
        }
    }
}

object Engine {
    private val ctx get() = DownloadCenter.ctx

    fun tmpPath(t: Task): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tmp/${t.id}")
    private fun tmpDir(t: Task): File = tmpPath(t).also { it.mkdirs() }

    private fun <T> withRetry(ok: () -> Boolean, block: () -> T): T {
        var a = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                a++
                if (!ok() || a >= 4) throw e
                Thread.sleep(1000L * a)
            }
        }
    }

    // ---------- تحميل ملف مباشر بأجزاء متوازية ----------
    fun direct(t: Task, alive: () -> Boolean) {
        val dir = tmpDir(t)
        val p = Net.probe(t.url, t.headers)
        t.total = p.total
        t.rangeOk = p.rangeOk
        val hasParts = (dir.listFiles()?.any { it.name.matches(Regex("p\\d+")) } ?: false) && t.parts > 0
        val n = if (!t.rangeOk || t.total < 2_000_000) 1
        else if (hasParts) t.parts
        else Prefs.parts(ctx).coerceIn(1, 16)
        t.parts = n

        val ranges = ArrayList<LongArray>()
        if (n == 1) {
            ranges.add(longArrayOf(0L, if (t.total > 0) t.total - 1 else -1L))
        } else {
            val chunk = t.total / n
            for (i in 0 until n) {
                val s = i * chunk
                val e = if (i == n - 1) t.total - 1 else s + chunk - 1
                ranges.add(longArrayOf(s, e))
            }
        }
        var have = 0L
        for (i in ranges.indices) {
            val f = File(dir, "p$i")
            if (!t.rangeOk) f.delete()
            val len = ranges[i][1] - ranges[i][0] + 1
            have += if (ranges[i][1] >= 0) minOf(f.length(), len) else f.length()
        }
        t.downloaded.set(have)
        t.lastBytes = have

        val err = AtomicReference<Exception?>(null)
        val ok = { alive() && err.get() == null }
        val threads = ranges.mapIndexed { i, r ->
            Thread {
                try {
                    withRetry(ok) { partOnce(t, dir, i, r[0], r[1], ok) }
                } catch (e: Exception) {
                    err.compareAndSet(null, e)
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        err.get()?.let { throw it }
        if (!alive()) return
        var sum = 0L
        for (i in ranges.indices) sum += File(dir, "p$i").length()
        if (t.total > 0 && sum != t.total) throw IOException("الملف غير مكتمل")
        if (t.total <= 0) t.total = sum
    }

    private fun partOnce(t: Task, dir: File, i: Int, start: Long, end: Long, ok: () -> Boolean) {
        val f = File(dir, "p$i")
        if (!t.rangeOk && f.exists()) {
            t.downloaded.addAndGet(-f.length())
            f.delete()
        }
        val have = f.length()
        if (end >= 0 && start + have > end) return
        val rs = start + have
        val range = if (t.rangeOk) "bytes=$rs-" + (if (end >= 0) end.toString() else "") else null
        val c = Net.connect(t.url, t.headers, range)
        try {
            val code = c.responseCode
            if (t.rangeOk && code != 206) throw IOException("HTTP $code")
            if (!t.rangeOk && code != 200) throw IOException("HTTP $code")
            val buf = ByteArray(64 * 1024)
            var remaining = if (end >= 0) end - rs + 1 else Long.MAX_VALUE
            c.inputStream.use { inp ->
                FileOutputStream(f, true).use { out ->
                    while (ok() && remaining > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                        t.downloaded.addAndGet(n.toLong())
                    }
                }
            }
            if (ok() && end >= 0 && remaining > 0) throw IOException("انقطع الاتصال")
        } finally {
            c.disconnect()
        }
    }

    // ---------- تحميل HLS (m3u8) ----------
    fun hls(t: Task, alive: () -> Boolean) {
        val dir = tmpDir(t)
        val media = Hls.parseMedia(Net.fetchText(t.url, t.headers), t.url)
        if (media.segs.isEmpty()) throw IOException("لا توجد مقاطع للتحميل")
        t.segCount = media.segs.size
        val initUrl = media.initUrl
        if (initUrl != null) {
            val f = File(dir, "init")
            if (!f.exists()) Net.saveBytes(initUrl, t.headers, f)
        }
        var done = 0
        var bytes = 0L
        for (i in media.segs.indices) {
            val f = File(dir, "s$i")
            if (f.exists()) { done++; bytes += f.length() }
        }
        t.segDone.set(done)
        t.downloaded.set(bytes)
        t.lastBytes = bytes

        val next = AtomicInteger(0)
        val err = AtomicReference<Exception?>(null)
        val keys = ConcurrentHashMap<String, ByteArray>()
        val ok = { alive() && err.get() == null }
        val n = Prefs.parts(ctx).coerceIn(1, 16)
        val threads = (0 until n).map {
            Thread {
                try {
                    while (ok()) {
                        val i = next.getAndIncrement()
                        if (i >= media.segs.size) break
                        val f = File(dir, "s$i")
                        if (f.exists()) continue
                        val s = media.segs[i]
                        var data = withRetry(ok) { Net.readBytes(s.url, t.headers) }
                        val ku = s.keyUri
                        if (ku != null) {
                            val key = keys.getOrPut(ku) { Net.readBytes(ku, t.headers) }
                            data = Hls.decrypt(data, key, s.iv, s.seq)
                        }
                        if (!ok()) break
                        val tmp = File(dir, "s$i.tmp")
                        tmp.writeBytes(data)
                        tmp.renameTo(f)
                        t.segDone.incrementAndGet()
                        t.downloaded.addAndGet(data.size.toLong())
                    }
                } catch (e: Exception) {
                    err.compareAndSet(null, e)
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        err.get()?.let { throw it }
        if (!alive()) return
        if (t.segDone.get() < t.segCount) throw IOException("بعض المقاطع ناقصة")
    }

    // ---------- دمج الأجزاء وحفظ الملف النهائي ----------
    private class Dest(val out: OutputStream, val uri: Uri, val name: String, val finish: () -> Unit)

    private fun openDest(name: String, mime: String): Dest {
        val tree = Prefs.folder(ctx)
        if (tree != null) {
            val dirDoc = DocumentFile.fromTreeUri(ctx, Uri.parse(tree))
                ?: throw IOException("مسار الحفظ غير صالح، اختر مجلدًا آخر من الإعدادات")
            var n = name
            var k = 1
            while (dirDoc.findFile(n) != null) {
                n = name.substringBeforeLast('.') + " ($k)." + name.substringAfterLast('.', "mp4")
                k++
            }
            val f = dirDoc.createFile(mime, n) ?: throw IOException("تعذر إنشاء الملف في المجلد المختار")
            val os = ctx.contentResolver.openOutputStream(f.uri) ?: throw IOException("تعذر فتح الملف للكتابة")
            return Dest(os, f.uri, f.name ?: n) {}
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoBrowser")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("تعذر إنشاء الملف")
        val os = resolver.openOutputStream(uri) ?: throw IOException("تعذر فتح الملف للكتابة")
        return Dest(os, uri, name) {
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, v, null, null)
        }
    }

    fun merge(t: Task) {
        val dir = tmpPath(t)
        val files = ArrayList<File>()
        var name = t.name
        if (t.kind == "hls") {
            val ini = File(dir, "init")
            if (ini.exists()) {
                files.add(ini)
                if (name.endsWith(".ts", true)) name = name.dropLast(2) + "mp4"
            }
            for (i in 0 until t.segCount) files.add(File(dir, "s$i"))
        } else {
            for (i in 0 until maxOf(t.parts, 1)) files.add(File(dir, "p$i"))
        }
        for (f in files) if (!f.exists()) throw IOException("ملف مؤقت مفقود")
        val mime = Util.mimeOf(name)
        val d = openDest(name, mime)
        try {
            d.out.use { o ->
                for (f in files) FileInputStream(f).use { it.copyTo(o, 256 * 1024) }
            }
            d.finish()
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(d.uri, null, null) } catch (e2: Exception) {}
            throw e
        }
        t.total = files.sumOf { it.length() }
        t.savedUri = d.uri.toString()
        t.name = d.name
        t.mime = mime
        dir.deleteRecursively()
    }
}
