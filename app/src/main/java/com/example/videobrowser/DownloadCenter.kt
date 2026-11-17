package com.example.videobrowser

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.ceil
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
        if (end >= 0 && start + have > end + 1L) throw IOException("حجم الجزء المؤقت أكبر من النطاق المطلوب")
        if (end >= 0 && start + have == end + 1L) return
        val rs = start + have
        val range = if (t.rangeOk) "bytes=$rs-" + (if (end >= 0) end.toString() else "") else null
        val c = Net.connect(t.url, t.headers, range)
        try {
            val code = c.responseCode
            if (t.rangeOk) {
                if (code != 206) throw IOException("الخادم لم يحترم طلب Range (HTTP $code)")
                val cr = Net.parseContentRange(c.getHeaderField("Content-Range"))
                    ?: throw IOException("استجابة Range بلا Content-Range صالح")
                val expectedEnd = if (end >= 0) end else cr.end
                if (cr.start != rs || cr.end != expectedEnd || (t.total > 0 && cr.total != t.total)) {
                    throw IOException("نطاق البايتات الذي أعاده الخادم لا يطابق الجزء المطلوب")
                }
                val expectedLength = cr.end - cr.start + 1L
                if (c.contentLengthLong >= 0L && c.contentLengthLong != expectedLength) {
                    throw IOException("حجم جزء HTTP لا يطابق Content-Range")
                }
            } else if (code != 200) {
                throw IOException("HTTP $code")
            }
            val expected = if (end >= 0) end - rs + 1L else c.contentLengthLong
            var received = 0L
            val buf = ByteArray(64 * 1024)
            c.inputStream.use { inp ->
                FileOutputStream(f, true).use { out ->
                    while (ok() && (expected < 0L || received < expected)) {
                        val limit = if (expected >= 0L) minOf(buf.size.toLong(), expected - received).toInt() else buf.size
                        if (limit <= 0) break
                        val n = inp.read(buf, 0, limit)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        received += n.toLong()
                        t.downloaded.addAndGet(n.toLong())
                        if (pd != null && i < pd.size) pd[i] += n.toLong()
                    }
                }
            }
            if (ok() && expected >= 0L && received != expected) throw IOException("انقطع الاتصال قبل اكتمال جزء التنزيل")
        } finally {
            c.disconnect()
        }
    }

    // ---------- تحميل HLS (m3u8) ----------
    fun hls(t: Task, alive: () -> Boolean) {
        val dir = tmpDir(t)
        val playlistText = Net.fetchText(t.url, t.headers)
        val media = Hls.parseMedia(playlistText, t.url)
        if (media.segs.isEmpty()) throw IOException("لا توجد مقاطع للتحميل")

        // Resume only when the exact playlist content is unchanged. A matching segment
        // count alone is not enough: URLs, byte ranges, keys and sequence numbers may differ.
        val fingerprint = sha256(structureOf(media).toByteArray(Charsets.UTF_8))
        val marker = File(dir, "hls-manifest.sha256")
        val oldFingerprint = marker.takeIf { it.exists() }?.readText()?.trim()
        val hasOldSegments = dir.listFiles()?.any { it.name.matches(Regex("s\\d+")) || it.name == "init" } == true
        val mustReset = (oldFingerprint == null && hasOldSegments) ||
            (oldFingerprint != null && oldFingerprint != fingerprint) ||
            (t.segCount > 0 && t.segCount != media.segs.size)
        if (mustReset) {
            dir.listFiles()?.forEach { f ->
                if (f.name.matches(Regex("s\\d+")) || f.name.matches(Regex("s\\d+\\.tmp")) ||
                    f.name == "init" || f.name == "init.tmp" || f.name == "hls-source.tmp" ||
                    f.name == "hls-final.mp4" || f.name == "hls-final.ts" ||
                    f.name == "hls-final-discontinuous.mp4" || f.name == "hls-local-discontinuous.m3u8") f.delete()
            }
            t.segCount = 0
            t.segDone.set(0)
            t.downloaded.set(0)
            t.notice = when {
                oldFingerprint == null && hasOldSegments -> "أُعيد تنزيل المقاطع القديمة للتحقق من توافقها مع الإصدار الحالي"
                oldFingerprint != null && oldFingerprint != fingerprint -> "تغيّرت قائمة البث؛ أُعيد تنزيل المقاطع لضمان عدم خلط ملفات مختلفة"
                else -> "تغيّر عدد المقاطع؛ أُعيد تنزيلها"
            }
        }
        marker.writeText(fingerprint)
        t.segCount = media.segs.size
        t.expectedDurationSec = media.duration
        t.segmentDurations = media.segs.map { it.dur }.toDoubleArray()
        t.segmentDiscontinuities = media.segs.map { it.discontinuityBefore }.toBooleanArray()
        t.hasHlsDiscontinuity = media.hasDiscontinuity
        if (!media.endList) {
            t.notice = (t.notice.takeIf { it.isNotEmpty() }?.plus(" — ") ?: "") +
                "تنبيه: قائمة HLS لا تحتوي ENDLIST؛ قد تكون بثًا حيًا أو لقطة غير مكتملة"
        }
        if (media.hasDiscontinuity) {
            t.notice = (t.notice.takeIf { it.isNotEmpty() }?.plus(" — ") ?: "") +
                "تحتوي القائمة على فواصل زمنية؛ ستُعاد معالجة المقاطع بالتتابع لإنتاج MP4 بجدول توقيت موحّد"
        }

        val keys = ConcurrentHashMap<String, ByteArray>()
        fun keyFor(uri: String): ByteArray = keys[uri] ?: synchronized(keys) {
            keys[uri] ?: Net.readBytes(uri, t.headers).also {
                if (it.size != 16) throw IOException("حجم مفتاح AES-128 غير صحيح")
                keys[uri] = it
            }
        }

        media.init?.let { init ->
            val f = File(dir, "init")
            if (!f.exists()) {
                var data = if (init.rangeStart != null && init.rangeLength != null) {
                    Net.readRangeBytes(init.url, t.headers, init.rangeStart, init.rangeLength)
                } else {
                    Net.readBytes(init.url, t.headers)
                }
                if (init.keyUri != null) data = Hls.decrypt(data, keyFor(init.keyUri), init.iv, 0L)
                val tmp = File(dir, "init.tmp")
                tmp.writeBytes(data)
                if (!tmp.renameTo(f)) {
                    tmp.delete()
                    throw IOException("تعذر حفظ ملف تهيئة HLS")
                }
            }
        }

        var done = 0
        var bytes = 0L
        for (i in media.segs.indices) {
            val f = File(dir, "s$i")
            if (f.exists() && f.length() > 0L) { done++; bytes += f.length() }
        }
        val segSt = BooleanArray(media.segs.size) { File(dir, "s$it").let { f -> f.exists() && f.length() > 0L } }
        t.segState = segSt
        t.segDone.set(done)
        t.downloaded.set(bytes)
        t.lastBytes = bytes

        val err = AtomicReference<Exception?>(null)
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
                        if (f.exists() && f.length() > 0L) continue
                        val seg = media.segs[i]
                        var data = withRetry(ok) {
                            if (seg.rangeStart != null && seg.rangeLength != null) {
                                Net.readRangeBytes(seg.url, t.headers, seg.rangeStart, seg.rangeLength)
                            } else Net.readBytes(seg.url, t.headers)
                        }
                        if (seg.keyUri != null) data = Hls.decrypt(data, keyFor(seg.keyUri), seg.iv, seg.seq)
                        if (!ok()) break
                        if (data.isEmpty()) throw IOException("المقطع ${i + 1} فارغ")
                        val tmp = File(dir, "s$i.tmp")
                        tmp.writeBytes(data)
                        if (!tmp.renameTo(f)) {
                            tmp.delete()
                            throw IOException("تعذر حفظ المقطع ${i + 1}")
                        }
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
        if (t.segDone.get() < t.segCount || media.segs.indices.any { !File(dir, "s$it").let { f -> f.exists() && f.length() > 0L } }) {
            throw IOException("بعض مقاطع HLS ناقصة")
        }
    }

    /**
     * بصمة بنية القائمة (عدد المقاطع، المدد، النطاقات، الفواصل) دون عناوين URL،
     * كي لا يُحذف ما تم تنزيله عند تغيّر رموز الروابط المؤقتة (token) أو بعد تحديث رابط التحميل.
     */
    private fun structureOf(m: Hls.Media): String {
        val sb = StringBuilder()
        sb.append("v2|").append(m.segs.size).append('|').append(m.endList).append('|')
        val ini = m.init
        if (ini != null) sb.append("init:").append(ini.rangeStart).append(':').append(ini.rangeLength).append('|')
        for (s in m.segs) {
            sb.append(s.seq).append(':')
                .append(String.format(Locale.US, "%.3f", s.dur)).append(':')
                .append(s.rangeStart).append(':').append(s.rangeLength).append(':')
                .append(if (s.discontinuityBefore) 1 else 0).append(':')
                .append(if (s.keyUri != null) 1 else 0).append(';')
        }
        return sb.toString()
    }

    /** يتأكد من وجود مساحة كافية للملف الوسيط والملف النهائي قبل بدء الدمج */
    private fun ensureFreeSpace(dir: File, sources: List<File>) {
        val bytes = sources.sumOf { it.length() }
        val need = (bytes.toDouble() * 2.1).toLong() + 128L * 1024L * 1024L
        val free = dir.usableSpace
        if (free < need) {
            throw IOException(
                "المساحة الحرة غير كافية للدمج: المتاح " + formatBytes(free) + "، والمطلوب تقريبًا " + formatBytes(need) +
                    ". حرّر مساحة ثم أعد المحاولة؛ لن تُحذف المقاطع التي تم تنزيلها."
            )
        }
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }

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
        var temporaryHlsOutput: File? = null

        if (t.kind == "hls") {
            val init = File(dir, "init")
            if (init.exists()) files.add(init)
            for (i in 0 until t.segCount) files.add(File(dir, "s$i"))
            for (f in files) if (!f.exists() || f.length() == 0L) throw IOException("مقطع HLS مفقود أو فارغ")

            // Never label a byte-concatenated TS/FMP4 stream as MP4. Use the
            // container-appropriate path, and rebuild timestamp discontinuities as
            // a sequence of independent media items before saving the final file.
            ensureFreeSpace(dir, files)
            val hasInitMap = init.exists()
            val rebuilt = if (hasInitMap) File(dir, "hls-final.mp4") else File(dir, "hls-final.ts")
            if (rebuilt.exists()) rebuilt.delete()
            try {
                if (hasInitMap) {
                    // fMP4 segments need their initialization section and a newly-built
                    // MP4 sample table/index so duration and seeking are correct.
                    remuxHlsSegmentsToMp4(dir, t.segCount, t.segmentDurations, t.expectedDurationSec, rebuilt)
                    verifyHlsDuration(rebuilt, t.expectedDurationSec)
                    name = withExtension(name, "mp4")
                } else if (t.hasHlsDiscontinuity) {
                    // A discontinuity can reset timestamps or change encoding/track
                    // parameters. Treat each TS segment as a separate Media3 input and
                    // compose them sequentially so the output timeline is rebuilt.
                    // Try lossless transmuxing first; fall back to device transcoding
                    // only if the segment sample formats are not mutually compatible.
                    val discontinuousMp4 = File(dir, "hls-final-discontinuous.mp4")
                    if (discontinuousMp4.exists()) discontinuousMp4.delete()
                    try {
                        rebuildDiscontinuousTransportStream(
                            dir, t.segCount, t.segmentDurations, t.segmentDiscontinuities,
                            t.expectedDurationSec, discontinuousMp4
                        )
                    } catch (e: Exception) {
                        discontinuousMp4.delete()
                        throw e
                    }
                    // Use the remuxed MP4 as the final intermediate file.
                    temporaryHlsOutput = discontinuousMp4
                    name = withExtension(name, "mp4")
                } else {
                    // A continuous MPEG-TS playlist can be preserved losslessly as TS.
                    concatenateHlsTransportStream(dir, t.segCount, rebuilt)
                    name = withExtension(name, "ts")
                }
            } catch (e: Exception) {
                rebuilt.delete()
                throw e
            }
            if (temporaryHlsOutput == null) temporaryHlsOutput = rebuilt
            files.clear()
            files.add(temporaryHlsOutput!!)
        } else {
            for (i in 0 until maxOf(t.parts, 1)) files.add(File(dir, "p$i"))
        }

        for (f in files) if (!f.exists() || f.length() == 0L) throw IOException("ملف مؤقت مفقود أو فارغ")
        val mime = if (t.kind == "hls") {
            if (name.substringAfterLast('.', "").equals("ts", true)) "video/mp2t" else "video/mp4"
        } else Util.mimeOf(name)
        val finalBytes = files.sumOf { it.length() }
        val d = openDest(name, mime)
        try {
            d.out.use { o ->
                for (f in files) FileInputStream(f).use { it.copyTo(o, 256 * 1024) }
            }
            d.finish()
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(d.uri, null, null) } catch (e2: Exception) {}
            throw e
        } finally {
            temporaryHlsOutput?.delete()
        }
        t.total = finalBytes
        t.savedUri = d.uri.toString()
        t.name = d.name
        t.mime = mime
        dir.deleteRecursively()
    }

    /**
     * Rebuild an MPEG-TS HLS playlist containing discontinuities. The preferred path
     * recreates a local VOD manifest with the original EXT-X-DISCONTINUITY boundaries
     * and lets Media3's HLS pipeline normalize timestamp-adjuster periods. If that
     * adaptive HLS export is unsupported by a device/source, fall back to a sequential
     * composition of the already-downloaded TS segments.
     */
    @OptIn(UnstableApi::class)
    private fun rebuildDiscontinuousTransportStream(
        dir: File,
        segmentCount: Int,
        durations: DoubleArray?,
        discontinuities: BooleanArray?,
        expectedDurationSec: Double,
        output: File
    ) {
        val safeDurations = durations
            ?: throw IOException("قائمة مدد مقاطع HLS غير متاحة لإعادة ضبط التوقيت")
        if (safeDurations.size != segmentCount || safeDurations.any { !it.isFinite() || it <= 0.0 }) {
            throw IOException("قائمة مدد مقاطع HLS غير مكتملة أو غير صالحة")
        }
        val safeDiscontinuities = discontinuities?.takeIf { it.size == segmentCount }
            ?: throw IOException("حدود EXT-X-DISCONTINUITY غير متاحة لإعادة بناء الفيديو")
        var downloadedBytes = 0L
        for (index in 0 until segmentCount) {
            val segment = File(dir, "s$index")
            if (!segment.exists() || segment.length() == 0L) {
                throw IOException("مقطع HLS ${index + 1} مفقود أو فارغ")
            }
            downloadedBytes = Math.addExact(downloadedBytes, segment.length())
        }

        // The source segments must remain available while Transformer writes a new MP4.
        // Require a conservative amount of free space to avoid a late ENOSPC failure
        // after a multi-gigabyte export has already started.
        val requiredFreeBytes = (downloadedBytes.toDouble() * 1.10).toLong() + 128L * 1024L * 1024L
        val availableBytes = dir.usableSpace
        if (availableBytes < requiredFreeBytes) {
            throw IOException(
                "المساحة الحرة غير كافية لإعادة تجميع فيديو HLS. " +
                    "المقاطع الحالية: ${formatBytes(downloadedBytes)}، " +
                    "المساحة الحرة: ${formatBytes(availableBytes)}، " +
                    "المطلوب التقريبي: ${formatBytes(requiredFreeBytes)}. " +
                    "حرّر مساحة إضافية ثم أعد المحاولة؛ لن تُحذف المقاطع التي تم تنزيلها."
            )
        }

        var manifestFailure: Exception? = null
        try {
            val localManifest = writeLocalDiscontinuousPlaylist(
                dir, segmentCount, safeDurations, safeDiscontinuities
            )
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(localManifest))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            output.delete()
            runTransformerExport(output) { transformer ->
                transformer.start(mediaItem, output.absolutePath)
            }
            verifyHlsDuration(output, expectedDurationSec)
            return
        } catch (e: Exception) {
            manifestFailure = e
            output.delete()
        }

        // Fallback: feed each TS segment as an independent item. First try bitstream
        // passthrough (no quality loss), then allow decoding/encoding if formats differ.
        val editedItems = ArrayList<EditedMediaItem>(segmentCount)
        for (index in 0 until segmentCount) {
            val segment = File(dir, "s$index")
            val itemBuilder = EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(segment))
                    .setMimeType(MimeTypes.VIDEO_MP2T)
                    .build()
            )
            val durationUs = (safeDurations[index] * 1_000_000.0).toLong().coerceAtLeast(1L)
            itemBuilder.setDurationUs(durationUs)
            editedItems.add(itemBuilder.build())
        }
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)

        var passthroughFailure: Exception? = null
        try {
            output.delete()
            val composition = Composition.Builder(sequence)
                .setTransmuxAudio(true)
                .setTransmuxVideo(true)
                .build()
            runTransformerExport(output) { transformer ->
                transformer.start(composition, output.absolutePath)
            }
            verifyHlsDuration(output, expectedDurationSec)
            return
        } catch (e: Exception) {
            passthroughFailure = e
            output.delete()
        }

        try {
            val composition = Composition.Builder(sequence).build()
            runTransformerExport(output) { transformer ->
                transformer.start(composition, output.absolutePath)
            }
            verifyHlsDuration(output, expectedDurationSec)
        } catch (e: Exception) {
            output.delete()
            manifestFailure?.let { e.addSuppressed(it) }
            passthroughFailure?.let { e.addSuppressed(it) }
            throw IOException(
                "تعذر إعادة بناء مقاطع MPEG-TS المتقطعة إلى MP4. قد تختلف مسارات/ترميزات المقاطع أو لا يدعم الجهاز أحدها: ${e.message ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    /** Creates a local VOD HLS manifest, preserving the exact discontinuity boundaries. */
    private fun writeLocalDiscontinuousPlaylist(
        dir: File,
        segmentCount: Int,
        durations: DoubleArray,
        discontinuities: BooleanArray
    ): File {
        val targetDuration = ceil(durations.maxOrNull() ?: 0.0).toInt().coerceAtLeast(1)
        val lines = ArrayList<String>(segmentCount * 3 + 8)
        lines += "#EXTM3U"
        lines += "#EXT-X-VERSION:3"
        lines += "#EXT-X-PLAYLIST-TYPE:VOD"
        lines += "#EXT-X-TARGETDURATION:$targetDuration"
        lines += "#EXT-X-MEDIA-SEQUENCE:0"
        for (index in 0 until segmentCount) {
            if (discontinuities[index]) lines += "#EXT-X-DISCONTINUITY"
            lines += "#EXTINF:${String.format(Locale.US, "%.6f", durations[index])},"
            lines += Uri.fromFile(File(dir, "s$index")).toString()
        }
        lines += "#EXT-X-ENDLIST"
        val playlist = File(dir, "hls-local-discontinuous.m3u8")
        playlist.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return playlist
    }

    /** Runs Media3 Transformer on its own Looper while the download worker awaits completion. */
    @OptIn(UnstableApi::class)
    private fun runTransformerExport(output: File, startExport: (Transformer) -> Unit) {
        val worker = HandlerThread("HlsDiscontinuityRemux").apply { start() }
        val handler = Handler(worker.looper)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val transformerRef = AtomicReference<Transformer?>(null)

        try {
            handler.post {
                try {
                    val transformer = Transformer.Builder(ctx)
                        .setLooper(worker.looper)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                done.countDown()
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                failure.set(exportException)
                                done.countDown()
                            }
                        })
                        .build()
                    transformerRef.set(transformer)
                    startExport(transformer)
                } catch (e: Throwable) {
                    failure.set(e)
                    done.countDown()
                }
            }

            val completed = done.await(12, TimeUnit.HOURS)
            if (!completed) {
                handler.post { try { transformerRef.get()?.cancel() } catch (_: Exception) {} }
                done.await(10, TimeUnit.SECONDS)
                throw IOException("تجاوزت إعادة بناء الفيديو الحد الزمني الآمن (12 ساعة)، وتم إيقاف العملية دون حذف المقاطع الأصلية")
            }
            failure.get()?.let { cause ->
                throw IOException("فشل Media3 Transformer: ${cause.message ?: cause.javaClass.simpleName}", cause)
            }
            if (!output.exists() || output.length() <= 0L) {
                throw IOException("انتهى تحويل Media3 دون إنشاء ملف MP4 صالح")
            }
        } finally {
            handler.post { try { transformerRef.get()?.removeAllListeners() } catch (_: Exception) {} }
            worker.quitSafely()
            try { worker.join(5_000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    /**
     * Concatenate MPEG-TS HLS media segments without changing their transport-stream
     * packet payload. Each segment is checked for 188-byte packet alignment and sync
     * bytes first; malformed/truncated data is rejected instead of being saved.
     */
    private fun concatenateHlsTransportStream(dir: File, segmentCount: Int, output: File) {
        var expectedSize = 0L
        for (index in 0 until segmentCount) {
            val segment = File(dir, "s$index")
            if (!segment.exists() || segment.length() < 188L || segment.length() % 188L != 0L) {
                throw IOException("مقطع HLS ${index + 1} ليس MPEG-TS مكتملًا (حجم الحزم غير صحيح)")
            }
            DataInputStream(BufferedInputStream(FileInputStream(segment))).use { input ->
                val packet = ByteArray(188)
                var packetIndex = 0
                while (true) {
                    val first = input.read()
                    if (first < 0) break
                    packet[0] = first.toByte()
                    try { input.readFully(packet, 1, 187) }
                    catch (e: EOFException) { throw IOException("مقطع HLS ${index + 1} ينتهي بحزمة TS ناقصة", e) }
                    if ((packet[0].toInt() and 0xff) != 0x47) {
                        throw IOException("مقطع HLS ${index + 1} لا يحتوي تزامن MPEG-TS صالحًا عند الحزمة ${packetIndex + 1}")
                    }
                    packetIndex++
                }
            }
            expectedSize += segment.length()
        }
        FileOutputStream(output).use { out ->
            for (index in 0 until segmentCount) {
                FileInputStream(File(dir, "s$index")).use { it.copyTo(out, 256 * 1024) }
            }
            out.fd.sync()
        }
        if (!output.exists() || output.length() != expectedSize) {
            output.delete()
            throw IOException("تعذر التحقق من اكتمال ملف MPEG-TS المجمع")
        }
    }

    private fun withExtension(name: String, ext: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "video" }
        return "$base.$ext"
    }

    /**
     * Remux HLS segments individually into a normal indexed MP4. Segment-local
     * timestamps are shifted onto the playlist timeline, so discontinuous TS/fMP4
     * timestamp origins do not make the final duration collapse or go backwards.
     */
    private fun remuxHlsSegmentsToMp4(
        dir: File,
        segmentCount: Int,
        durations: DoubleArray?,
        expectedDurationSec: Double,
        output: File
    ) {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val outputTrackBySourceOrder = ArrayList<Int>()
        val maxPtsByOutputTrack = ArrayList<Long>()
        var referenceSignatures: List<String>? = null
        var timelineUs = 0L
        val bufferInfo = MediaCodec.BufferInfo()
        var sharedBuffer: ByteBuffer? = null

        try {
            for (segmentIndex in 0 until segmentCount) {
                val segment = File(dir, "s$segmentIndex")
                if (!segment.exists() || segment.length() == 0L) throw IOException("مقطع HLS ${segmentIndex + 1} مفقود")
                val init = File(dir, "init")
                val source = if (init.exists()) {
                    val joined = File(dir, "hls-segment-$segmentIndex.tmp")
                    FileOutputStream(joined).use { out ->
                        FileInputStream(init).use { it.copyTo(out, 256 * 1024) }
                        FileInputStream(segment).use { it.copyTo(out, 256 * 1024) }
                        out.fd.sync()
                    }
                    joined
                } else segment

                try {
                    // First pass: get the track layout and the earliest timestamp in
                    // this segment without retaining sample data in memory.
                    val probe = MediaExtractor()
                    val sourceTracks: List<Int>
                    val signatures: List<String>
                    val firstPtsBySource = HashMap<Int, Long>()
                    try {
                        probe.setDataSource(source.absolutePath)
                        sourceTracks = (0 until probe.trackCount).filter { index ->
                            val mime = probe.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: ""
                            mime.startsWith("video/") || mime.startsWith("audio/")
                        }
                        if (sourceTracks.isEmpty()) throw IOException("مقطع HLS ${segmentIndex + 1} لا يحتوي مسارات صوت/فيديو قابلة للقراءة")
                        signatures = sourceTracks.map { mediaFormatSignature(probe.getTrackFormat(it)) }
                        sourceTracks.forEach { probe.selectTrack(it) }
                        while (probe.sampleTrackIndex >= 0) {
                            val track = probe.sampleTrackIndex
                            val pts = probe.sampleTime
                            if (pts >= 0L && !firstPtsBySource.containsKey(track)) firstPtsBySource[track] = pts
                            if (!probe.advance()) break
                        }
                    } finally {
                        probe.release()
                    }
                    if (sourceTracks.any { !firstPtsBySource.containsKey(it) }) {
                        throw IOException("المقطع ${segmentIndex + 1} لا يحتوي توقيتًا صالحًا لكل المسارات")
                    }

                    if (muxer == null) {
                        val setup = MediaExtractor()
                        try {
                            setup.setDataSource(source.absolutePath)
                            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            for (sourceTrack in sourceTracks) {
                                val outputTrack = muxer!!.addTrack(setup.getTrackFormat(sourceTrack))
                                outputTrackBySourceOrder.add(outputTrack)
                                maxPtsByOutputTrack.add(-1L)
                            }
                            muxer!!.start()
                            muxerStarted = true
                            referenceSignatures = signatures
                        } finally {
                            setup.release()
                        }
                    } else if (signatures != referenceSignatures) {
                        throw IOException("تغيّر ترميز أو عدد مسارات HLS بين المقاطع؛ أُوقف الدمج كي لا ينتج ملف صوت/فيديو تالف")
                    }

                    // Compute one shared offset for A/V sync. If the playlist duration
                    // overlaps a previous segment by a few ticks, move the whole segment
                    // forward just enough to keep each track chronological.
                    val minFirstPts = sourceTracks.minOf { firstPtsBySource.getValue(it) }
                    var offsetUs = timelineUs - minFirstPts
                    for (index in sourceTracks.indices) {
                        val first = firstPtsBySource.getValue(sourceTracks[index]) + offsetUs
                        val previousMax = maxPtsByOutputTrack[index]
                        if (previousMax >= 0L && first <= previousMax) {
                            offsetUs = maxOf(offsetUs, previousMax + 1L - firstPtsBySource.getValue(sourceTracks[index]))
                        }
                    }
                    val actualSegmentStartUs = minFirstPts + offsetUs

                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(source.absolutePath)
                        sourceTracks.forEach { extractor.selectTrack(it) }
                        val maxInputSize = sourceTracks.mapNotNull { track ->
                            val format = extractor.getTrackFormat(track)
                            try { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } catch (_: Exception) { null }
                        }.maxOrNull() ?: 0
                        val capacity = (maxInputSize.toLong() + 64L * 1024L)
                            .coerceIn(4L * 1024L * 1024L, 32L * 1024L * 1024L).toInt()
                        val buffer: ByteBuffer = if (sharedBuffer != null && sharedBuffer!!.capacity() >= capacity) sharedBuffer!!
                        else ByteBuffer.allocateDirect(capacity).also { sharedBuffer = it }
                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val sourceTrack = extractor.sampleTrackIndex
                            val sourceOrder = sourceTracks.indexOf(sourceTrack)
                            if (sourceOrder < 0) {
                                if (!extractor.advance()) break
                                continue
                            }
                            if (size > buffer.capacity()) throw IOException("إطار فيديو يتجاوز الحد الآمن للذاكرة المؤقتة")
                            val sourcePts = extractor.sampleTime
                            if (sourcePts < 0L) throw IOException("توقيت إطار غير صالح في المقطع ${segmentIndex + 1}")
                            val outputTrack = outputTrackBySourceOrder[sourceOrder]
                            val outputPts = sourcePts + offsetUs
                            // Samples can be emitted in decode order while presentation
                            // timestamps move backwards for B-frames. MediaMuxer supports
                            // B-frame MP4 output, so preserve extractor sample order and
                            // track the maximum PTS only for the next segment boundary.
                            buffer.position(0)
                            buffer.limit(size)
                            val muxFlags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            bufferInfo.set(0, size, outputPts, muxFlags)
                            muxer!!.writeSampleData(outputTrack, buffer, bufferInfo)
                            maxPtsByOutputTrack[outputTrack] = maxOf(maxPtsByOutputTrack[outputTrack], outputPts)
                            if (!extractor.advance()) break
                        }
                    } finally {
                        extractor.release()
                    }

                    val duration = durations?.getOrNull(segmentIndex)?.takeIf { it > 0.0 }
                        ?: if (expectedDurationSec > 0.0) expectedDurationSec / segmentCount else 0.0
                    timelineUs = maxOf(timelineUs, actualSegmentStartUs) + (duration * 1_000_000.0).toLong()
                } finally {
                    if (source != segment) source.delete()
                }
            }

            if (muxer == null || !muxerStarted || maxPtsByOutputTrack.isEmpty()) {
                throw IOException("لم يتم استخراج أي مسار صالح من فيديو HLS")
            }
            if (expectedDurationSec > 0.0) {
                val endUs = (expectedDurationSec * 1_000_000.0).toLong()
                val lastSegmentDuration = durations?.lastOrNull()?.takeIf { it > 0.0 } ?: 0.0
                val allowedTail = (maxOf(10.0, lastSegmentDuration + 2.0) * 1_000_000.0).toLong()
                if (maxPtsByOutputTrack.any { it < 0L || it + allowedTail < endUs }) {
                    throw IOException("بيانات أحد مسارات الصوت/الفيديو تنتهي قبل المدة المعلنة في قائمة HLS؛ رُفض الملف بدل إخفاء النقص بتمديد آخر إطار")
                }
            }
            muxer!!.stop()
            muxerStarted = false
            if (!output.exists() || output.length() <= 0L) throw IOException("فشل إنشاء ملف MP4 النهائي")
        } catch (e: Exception) {
            output.delete()
            if (e is IOException) throw e
            throw IOException("تعذر إعادة بناء مقاطع HLS إلى MP4 قابل للتقديم والتأخير: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            if (muxerStarted) try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun mediaFormatSignature(format: MediaFormat): String {
        val keys = listOf(
            MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT,
            MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL
        )
        val values = keys.joinToString("|") { key ->
            val stringValue = try { format.getString(key) } catch (_: Exception) { null }
            val intValue = if (stringValue == null) try { format.getInteger(key).toString() } catch (_: Exception) { "" } else ""
            "$key=${stringValue ?: intValue}"
        }
        val csd = (0..2).joinToString("|") { index ->
            try {
                val data = format.getByteBuffer("csd-$index")?.duplicate() ?: return@joinToString ""
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                bytes.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { "" }
        }
        return "$values|$csd"
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
        else String.format(Locale.US, "%.0f MB", mb)
    }

    private fun verifyHlsDuration(file: File, expectedSeconds: Double) {
        if (expectedSeconds <= 0.0) return
        val retriever = MediaMetadataRetriever()
        val durationMs: Long
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IOException("تعذر قراءة مدة ملف MP4 الناتج")
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("تعذر التحقق من مدة ملف MP4 الناتج", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        val actual = durationMs / 1000.0
        val tolerance = maxOf(8.0, expectedSeconds * 0.03)
        if (actual + tolerance < expectedSeconds) {
            throw IOException("الملف الناتج أقصر من مدة قائمة HLS (المتوقع ${expectedSeconds.toInt()} ثانية، الناتج ${actual.toInt()} ثانية). أُوقف الحفظ لتجنب ملف بمدة وهمية")
        }
        if (actual - tolerance > expectedSeconds) {
            throw IOException("مدة الملف الناتج لا تطابق مدة قائمة HLS (المتوقع ${expectedSeconds.toInt()} ثانية، الناتج ${actual.toInt()} ثانية). أُوقف الحفظ لحماية الملف من التوقيتات غير السليمة")
        }
    }

}
