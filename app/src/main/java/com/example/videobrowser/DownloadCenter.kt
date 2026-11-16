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
                        .put("mime", t.mime).put("segCount", t.segCount).put("rangeOk", t.rangeOk).put("thumbUrl", t.thumbUrl)
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
                t.thumbUrl = o.optString("thumbUrl", "")
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
        if (t.kind == "hls") {
            t.segDone.set(c)
            if (t.segCount > 0) t.segState = BooleanArray(t.segCount) { File(dir, "s$it").exists() }
        } else if (t.parts > 1 && t.total > 0) {
            val n = t.parts
            val chunk = t.total / n
            val sizes = LongArray(n) { i -> if (i == n - 1) t.total - chunk * (n - 1) else chunk }
            t.partSize = sizes
            t.partDone = LongArray(n) { i -> minOf(File(dir, "p$i").length(), sizes[i]) }
        }
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

    /** استبدال رابط التحميل (بعد انتهاء صلاحيته) ثم الاستئناف من حيث توقف إن أمكن */
    fun replaceSource(t: Task, newUrl: String, newHeaders: Map<String, String>) {
        if (t.status != St.PAUSED && t.status != St.FAILED) return
        t.url = newUrl
        t.headers = newHeaders
        t.error = ""
        t.notice = ""
        save()
        resume(t)
    }

    fun cancel(t: Task) {
        t.stop = true
        t.gen++
        tasks.remove(t)
        save(); changed()
        pool.execute { synchronized(t.lock) { Engine.tmpPath(t).deleteRecursively() } }
        startNext()
    }

    fun repair(t: Task, cb: (Boolean) -> Unit) {
        if (t.status != St.DONE || t.savedUri.isEmpty() || t.notice == "جارٍ إصلاح الملف...") return
        t.notice = "جارٍ إصلاح الملف..."
        changed()
        pool.execute {
            val ok = try { Engine.repair(t) } catch (e: Exception) { false }
            t.notice = if (ok) "" else "تعذّر إصلاح الملف"
            if (ok) save()
            main.post { changed(); cb(ok) }
        }
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
            t.notice = ""
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
        val oldTotal = t.total
        val hadParts = dir.listFiles()?.any { it.name.matches(Regex("p\\d+")) } ?: false
        if (hadParts && oldTotal > 0 && p.total > 0 && p.total != oldTotal) {
            // الملف الجديد يختلف في الحجم: لا يمكن الدمج مع الأجزاء القديمة
            dir.listFiles()?.forEach { it.delete() }
            t.parts = 0
            t.downloaded.set(0)
            t.notice = "تغيّر حجم الملف فبدأ التحميل من جديد"
        }
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
        val psz = LongArray(ranges.size) { i -> if (ranges[i][1] >= 0) ranges[i][1] - ranges[i][0] + 1 else 0L }
        t.partSize = psz
        t.partDone = LongArray(ranges.size) { i ->
            val fl = File(dir, "p$i").length()
            if (psz[i] > 0) minOf(fl, psz[i]) else fl
        }

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
        val pd = t.partDone
        if (pd != null && i < pd.size) pd[i] = have
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
                        if (pd != null && i < pd.size) pd[i] += n.toLong()
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
        if (t.segCount > 0 && t.segCount != media.segs.size) {
            dir.listFiles()?.forEach { it.delete() }
            t.notice = "تغيّر عدد المقاطع فبدأ التحميل من جديد"
        }
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
        val segSt = BooleanArray(media.segs.size) { File(dir, "s$it").exists() }
        t.segState = segSt
        t.segDone.set(done)
        t.downloaded.set(bytes)
        t.lastBytes = bytes

        val err = AtomicReference<Exception?>(null)
        val keys = ConcurrentHashMap<String, ByteArray>()
        val ok = { alive() && err.get() == null }
        val total = media.segs.size
        val n = minOf(Prefs.parts(ctx).coerceIn(1, 16), total)
        t.parts = n
        val threads = (0 until n).map { k ->
            val from = k * total / n
            val to = (k + 1) * total / n
            Thread {
                try {
                    for (i in from until to) {
                        if (!ok()) break
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
                        segSt[i] = true
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

    private fun swapExt(name: String, ext: String): String {
        val i = name.lastIndexOf('.')
        return (if (i > 0) name.substring(0, i) else name) + "." + ext
    }

    /** هل ملف MP4 مجزأ (fragmented) ؟ أي بلا فهرس كامل للتقديم */
    private fun isFragmented(f: File): Boolean {
        try {
            java.io.RandomAccessFile(f, "r").use { r ->
                var pos = 0L
                val len = r.length()
                val hdr = ByteArray(16)
                while (pos + 8 <= len) {
                    r.seek(pos)
                    r.readFully(hdr, 0, 8)
                    var size = ((hdr[0].toLong() and 0xff) shl 24) or ((hdr[1].toLong() and 0xff) shl 16) or
                        ((hdr[2].toLong() and 0xff) shl 8) or (hdr[3].toLong() and 0xff)
                    val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                    if (size == 1L) {
                        if (pos + 16 > len) return false
                        r.readFully(hdr, 8, 8)
                        size = 0L
                        for (k in 8 until 16) size = (size shl 8) or (hdr[k].toLong() and 0xff)
                    }
                    if (type == "moof") return true
                    if (type == "mdat") return false
                    if (size < 8) return false
                    pos += size
                }
            }
        } catch (e: Exception) {
        }
        return false
    }

    /** إصلاح ملف فيديو مكتمل: يعيد بناءه كـ MP4 سليم ثم يستبدل القديم */
    fun repair(t: Task): Boolean {
        val inF = File(ctx.cacheDir, "repair_in_" + t.id)
        val outF = File(ctx.cacheDir, "repair_out_" + t.id + ".mp4")
        try {
            val ins = ctx.contentResolver.openInputStream(Uri.parse(t.savedUri)) ?: return false
            ins.use { i -> FileOutputStream(inF).use { o -> i.copyTo(o, 256 * 1024) } }
            val res = Remux.run(inF, outF) ?: return false
            val newName = swapExt(t.name, if (res.hasVideo) "mp4" else "m4a")
            val mime = Util.mimeOf(newName)
            val d = openDest(newName, mime)
            try {
                d.out.use { o -> FileInputStream(outF).use { it.copyTo(o, 256 * 1024) } }
                d.finish()
            } catch (e: Exception) {
                try { ctx.contentResolver.delete(d.uri, null, null) } catch (x: Exception) {}
                return false
            }
            val oldUri = t.savedUri
            t.savedUri = d.uri.toString()
            t.name = d.name
            t.mime = mime
            t.total = outF.length()
            try {
                val u = Uri.parse(oldUri)
                if (DocumentsContract.isDocumentUri(ctx, u)) DocumentsContract.deleteDocument(ctx.contentResolver, u)
                else ctx.contentResolver.delete(u, null, null)
            } catch (e: Exception) {
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            inF.delete()
            outF.delete()
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

        // تحويل إلى MP4 سليم (يصلح التقديم والتأخير والمدة)
        var src: List<File> = files
        val ext0 = name.substringAfterLast('.', "").lowercase()
        val candidate = Prefs.remux(ctx) && (t.kind == "hls" || ext0 == "ts" ||
            ((ext0 == "mp4" || ext0 == "m4v" || ext0 == "mov") && isFragmented(files[0])))
        if (candidate) {
            var ok = false
            try {
                val merged = File(dir, "merged.tmp")
                FileOutputStream(merged).use { o -> for (f in files) FileInputStream(f).use { it.copyTo(o, 256 * 1024) } }
                val outF = File(dir, "remux.mp4")
                if (outF.exists()) outF.delete()
                val res = Remux.run(merged, outF)
                merged.delete()
                if (res != null && outF.length() > 0) {
                    src = listOf(outF)
                    name = swapExt(name, if (res.hasVideo) "mp4" else "m4a")
                    ok = true
                } else {
                    outF.delete()
                }
            } catch (e: Exception) {
            }
            if (!ok) t.notice = "تعذّر تحويل الملف إلى MP4، حُفظ بصيغته الأصلية وقد لا يدعم التقديم"
        }

        val mime = Util.mimeOf(name)
        val d = openDest(name, mime)
        try {
            d.out.use { o ->
                for (f in src) FileInputStream(f).use { it.copyTo(o, 256 * 1024) }
            }
            d.finish()
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(d.uri, null, null) } catch (e2: Exception) {}
            throw e
        }
        t.total = src.sumOf { it.length() }
        t.savedUri = d.uri.toString()
        t.name = d.name
        t.mime = mime
        dir.deleteRecursively()
    }
}
