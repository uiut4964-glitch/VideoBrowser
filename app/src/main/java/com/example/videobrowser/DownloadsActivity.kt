package com.example.videobrowser

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DownloadsActivity : AppCompatActivity() {
    private var mode = 0 // 0 = قيد التحميل ، 1 = المنتهية
    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var tab0: TextView
    private lateinit var tab1: TextView
    private lateinit var ind0: View
    private lateinit var ind1: View
    private val adapter = Adapter()
    private val listener: () -> Unit = { refresh() }
    private var selecting = false
    private val selected = HashSet<String>()
    private lateinit var titleTv: TextView

    private inner class VH(
        val root: LinearLayout, val thumb: ImageView, val dur: TextView, val name: TextView,
        val bar: SegmentBar, val info: TextView, val btnA: ImageButton, val btnB: ImageButton,
        val check: CheckBox
    ) : RecyclerView.ViewHolder(root) {
        var task: Task? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        val dark = 0xFF37474F.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F4F5.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        header.addView(iconBtn(R.drawable.ic_back, dark).apply {
            setOnClickListener { if (selecting) exitSelect() else finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleTv = TextView(this).apply { text = "التحميلات"; textSize = 19f; setTextColor(Color.BLACK) }
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        header.addView(iconBtn(R.drawable.ic_check_box, dark).apply { setOnClickListener { selectAll() } },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(iconBtn(R.drawable.ic_delete, dark).apply { setOnClickListener { deleteSelected() } },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) exitSelect() else finish()
            }
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE) }
        fun mk(m: Int): Pair<TextView, View> {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { mode = m; selected.clear(); refresh() } }
            val tv = TextView(this).apply {
                textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(10))
            }
            val ind = View(this)
            col.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(ind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            tabs.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return tv to ind
        }
        val a = mk(0); tab0 = a.first; ind0 = a.second
        val b = mk(1); tab1 = b.first; ind1 = b.second
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(0xFFDADCE0.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val frame = FrameLayout(this)
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            adapter = this@DownloadsActivity.adapter
            itemAnimator = null
            setPadding(0, dp(6), 0, dp(12))
            clipToPadding = false
        }
        empty = TextView(this).apply {
            text = "لا توجد عناصر"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
        }
        frame.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        DownloadCenter.addListener(listener)
        refresh()
    }

    override fun onStop() {
        DownloadCenter.removeListener(listener)
        super.onStop()
    }

    private fun refresh() {
        val all = DownloadCenter.tasks
        selected.retainAll(all.map { it.id }.toSet())
        updateTitle()
        val active = all.count { it.status != St.DONE }
        val done = all.count { it.status == St.DONE }
        adapter.data = all.filter { (it.status == St.DONE) == (mode == 1) }
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.data.isEmpty()) View.VISIBLE else View.GONE
        tab0.text = "قيد التحميل ($active)"
        tab1.text = "المنتهية ($done)"
        val blue = 0xFF1565C0.toInt()
        val grey = 0xFF78909C.toInt()
        tab0.setTextColor(if (mode == 0) blue else grey)
        tab1.setTextColor(if (mode == 1) blue else grey)
        ind0.setBackgroundColor(if (mode == 0) blue else Color.TRANSPARENT)
        ind1.setBackgroundColor(if (mode == 1) blue else Color.TRANSPARENT)
    }

    // ---------------------------------------------------------------- التحديد المتعدد
    private fun updateTitle() {
        titleTv.text = if (selecting) selected.size.toString() + " محدد" else "التحميلات"
    }

    private fun startSelect(t: Task) {
        selecting = true
        selected.add(t.id)
        updateTitle()
        adapter.notifyDataSetChanged()
    }

    private fun toggle(t: Task) {
        if (!selected.remove(t.id)) selected.add(t.id)
        updateTitle()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelect() {
        selecting = false
        selected.clear()
        updateTitle()
        adapter.notifyDataSetChanged()
    }

    private fun selectAll() {
        val vis = adapter.data
        if (vis.isEmpty()) return
        selecting = true
        if (vis.all { selected.contains(it.id) }) selected.clear() else selected.addAll(vis.map { it.id })
        updateTitle()
        adapter.notifyDataSetChanged()
    }

    private fun deleteSelected() {
        if (!selecting || selected.isEmpty()) {
            Toast.makeText(this, "اضغط زر التحديد ثم اختر الملفات التي تريد حذفها", Toast.LENGTH_LONG).show()
            if (!selecting) {
                selecting = true
                updateTitle()
                adapter.notifyDataSetChanged()
            }
            return
        }
        val list = DownloadCenter.tasks.filter { selected.contains(it.id) }
        confirm("حذف " + list.size + " عنصر؟ سيتم حذف الملفات المكتملة من الجهاز وإلغاء التحميلات غير المكتملة.") {
            for (t in list) {
                if (t.status == St.DONE) DownloadCenter.deleteFinished(t) else DownloadCenter.cancel(t)
            }
            exitSelect()
        }
    }

    // ---------------------------------------------------------------- خيارات التحميل غير المكتمل
    private fun showTaskMenu(t: Task, anchor: View) {
        val pm = PopupMenu(this, anchor)
        val canFix = t.status == St.PAUSED || t.status == St.FAILED
        if (canFix) {
            pm.menu.add(0, 1, 0, "تحديث رابط التحميل (من صفحة الفيديو)")
            pm.menu.add(0, 2, 1, "لصق رابط جديد")
        }
        pm.menu.add(0, 3, 2, "إلغاء وحذف")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> refreshFromPage(t)
                2 -> pasteUrl(t)
                3 -> confirm("إلغاء هذا التحميل وحذف ما تم تحميله؟") { DownloadCenter.cancel(t) }
            }
            true
        }
        pm.show()
    }

    /** يفتح صفحة الفيديو الأصلية في المتصفح ليلتقط رابطًا جديدًا ويكمل التحميل */
    private fun refreshFromPage(t: Task) {
        val page = t.headers["Referer"] ?: t.url
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("refreshTask", t.id)
                .putExtra("refreshUrl", page)
        )
    }

    private fun pasteUrl(t: Task) {
        val et = EditText(this).apply {
            hint = "https://..."
            setSingleLine()
            textDirection = View.TEXT_DIRECTION_LTR
        }
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (c != null && c.startsWith("http")) et.setText(c)
        } catch (e: Exception) {
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this)
            .setTitle("رابط التحميل الجديد")
            .setMessage("ألصق رابط نفس الملف. إن كان بنفس الحجم يكمل التحميل من حيث توقف، وإلا يبدأ من جديد.")
            .setView(box)
            .setPositiveButton("تحديث") { _, _ ->
                val u = et.text.toString().trim()
                if (u.startsWith("http")) {
                    DownloadCenter.replaceSource(t, u, t.headers)
                    Toast.makeText(this, "تم تحديث الرابط واستئناف التحميل", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "الرابط غير صالح", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun infoText(t: Task): String {
        val st = when (t.status) {
            St.QUEUED -> "في الانتظار"
            St.DOWNLOADING -> "جارٍ التحميل"
            St.PAUSED -> "متوقف مؤقتًا"
            St.MERGING -> "جارٍ دمج الملف..."
            St.FAILED -> "فشل: " + t.error
            else -> ""
        }
        val size = if (t.kind == "hls") {
            t.segDone.get().toString() + "/" + t.segCount + " مقطع • " + Util.fmtSize(t.downloaded.get())
        } else {
            Util.fmtSize(t.downloaded.get()) + (if (t.total > 0) " / " + Util.fmtSize(t.total) else "")
        }
        val sp = if (t.status == St.DOWNLOADING && t.speed > 0) "\n" + Util.fmtSpeed(t.speed) else ""
        val pp = if (t.parts > 1) " • " + t.parts + " أجزاء" else ""
        val nt = if (t.notice.isNotEmpty()) "\n" + t.notice else ""
        return st + "\n" + size + pp + sp + nt
    }

    private fun bind(h: VH, t: Task) {
        val dark = 0xFF37474F.toInt()
        h.task = t
        h.name.text = t.name

        val kind = Util.kindOf(t.name)
        val th = if (kind == "video") ThumbLoader.thumbFor(t) else null
        if (th?.bmp != null) {
            h.thumb.clearColorFilter()
            h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            h.thumb.setImageBitmap(th.bmp)
        } else {
            h.thumb.scaleType = ImageView.ScaleType.CENTER
            h.thumb.setColorFilter(if (kind == "apk") 0xFF43A047.toInt() else 0xFF90A4AE.toInt())
            h.thumb.setImageResource(
                when (kind) {
                    "apk" -> R.drawable.ic_apk
                    "audio" -> R.drawable.ic_music
                    "video" -> R.drawable.ic_video
                    else -> R.drawable.ic_file
                }
            )
        }
        if (kind == "video") ThumbLoader.loadTask(this, t) { refresh() }
        h.check.visibility = if (selecting) View.VISIBLE else View.GONE
        h.check.isChecked = selected.contains(t.id)

        if (t.status == St.DONE) {
            h.bar.visibility = View.GONE
            h.info.text = Util.fmtSize(t.total)
            h.info.setTextColor(0xFF78909C.toInt())
            val d = th?.durMs ?: 0L
            h.dur.visibility = if (d > 0) View.VISIBLE else View.GONE
            if (d > 0) h.dur.text = Util.fmtDur(d / 1000.0)
            h.btnA.setImageResource(when (kind) { "apk" -> R.drawable.ic_apk; "video", "audio" -> R.drawable.ic_play; else -> R.drawable.ic_file })
            h.btnA.setColorFilter(0xFF1565C0.toInt())
            h.btnA.background = circleStroke()
            h.btnB.setImageResource(R.drawable.ic_more)
            h.btnB.setColorFilter(dark)
        } else {
            h.dur.visibility = View.GONE
            h.bar.visibility = View.VISIBLE
            h.bar.set(fractions(t))
            h.info.text = infoText(t)
            h.info.setTextColor(if (t.status == St.FAILED) 0xFFD32F2F.toInt() else 0xFF607D8B.toInt())
            h.btnA.setImageResource(
                when (t.status) {
                    St.PAUSED -> R.drawable.ic_play
                    St.FAILED -> R.drawable.ic_refresh
                    else -> R.drawable.ic_pause
                }
            )
            h.btnA.setColorFilter(dark)
            h.btnA.background = circleStroke()
            h.btnA.visibility = if (t.status == St.MERGING) View.INVISIBLE else View.VISIBLE
            h.btnB.setImageResource(R.drawable.ic_more)
            h.btnB.setColorFilter(dark)
        }
        if (selecting) {
            h.btnA.visibility = View.GONE
            h.btnB.visibility = View.GONE
        } else {
            h.btnB.visibility = View.VISIBLE
            if (t.status != St.MERGING) h.btnA.visibility = View.VISIBLE
        }
    }

    /** نطاقات التقدم (بداية، نهاية) كنسبة من الملف كاملًا */
    private fun fractions(t: Task): FloatArray {
        val out = ArrayList<Float>()
        if (t.kind == "hls") {
            val st = t.segState
            val n = st?.size ?: 0
            if (st != null && n > 0) {
                var i = 0
                while (i < n) {
                    if (st[i]) {
                        var j = i
                        while (j < n && st[j]) j++
                        out.add(i.toFloat() / n)
                        out.add(j.toFloat() / n)
                        i = j
                    } else {
                        i++
                    }
                }
                return out.toFloatArray()
            }
        } else {
            val d = t.partDone
            val sz = t.partSize
            if (d != null && sz != null && sz.size == d.size && t.total > 0) {
                var start = 0L
                for (i in d.indices) {
                    val dn = minOf(d[i], sz[i])
                    if (dn > 0) {
                        out.add(start.toFloat() / t.total)
                        out.add((start + dn).toFloat() / t.total)
                    }
                    start += sz[i]
                }
                return out.toFloatArray()
            }
        }
        return floatArrayOf(0f, t.permille() / 1000f)
    }

    private fun circleStroke() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(dp(1), 0xFFB0BEC5.toInt())
    }

    private fun play(t: Task) {
        when (Util.kindOf(t.name)) {
            "video", "audio" -> playMedia(t)
            "apk" -> install(t)
            else -> openExternal(t)
        }
    }

    private fun install(t: Task) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "اسمح لهذا التطبيق بتثبيت التطبيقات ثم ارجع واضغط تثبيت", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            } catch (e: Exception) {
            }
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(t.savedUri), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح المثبّت (ربما حُذف الملف)", Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternal(t: Task) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(t.savedUri), t.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "لا يوجد تطبيق لفتح هذا الملف", Toast.LENGTH_LONG).show()
        }
    }

    private fun playMedia(t: Task) {
        val done = DownloadCenter.tasks.filter {
            it.status == St.DONE && it.savedUri.isNotEmpty() && Util.kindOf(it.name).let { k -> k == "video" || k == "audio" }
        }
        if (done.isEmpty()) return
        val idx = done.indexOf(t).coerceAtLeast(0)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("uris", done.map { it.savedUri }.toTypedArray())
                .putExtra("names", done.map { it.name }.toTypedArray())
                .putExtra("index", idx)
        )
    }

    private fun share(t: Task) {
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(t.mime)
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(t.savedUri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "مشاركة"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "تعذرت المشاركة", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("نعم") { _, _ -> ok() }
            .setNegativeButton("لا", null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        var data: List<Task> = emptyList()

        init { setHasStableIds(true) }

        override fun getItemCount(): Int = data.size
        override fun getItemId(position: Int): Long = data[position].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = this@DownloadsActivity
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(10), c.dp(10), c.dp(6), c.dp(10))
                background = c.rounded(Color.WHITE, 12, 0xFFE0E4E7.toInt())
                elevation = c.dp(1).toFloat()
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(c.dp(10), c.dp(5), c.dp(10), c.dp(5)) }
            }
            // الصورة المصغرة + مدة الفيديو
            val tf = FrameLayout(c).apply {
                background = c.rounded(0xFFECEFF1.toInt(), 8)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            val thumb = ImageView(c)
            val dur = TextView(c).apply {
                textSize = 10f
                setTextColor(Color.WHITE)
                background = c.rounded(0xAA000000.toInt(), 4)
                setPadding(c.dp(4), c.dp(1), c.dp(4), c.dp(1))
                visibility = View.GONE
            }
            tf.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            tf.addView(dur, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START).apply { setMargins(c.dp(4), c.dp(4), c.dp(4), c.dp(4)) })
            val check = CheckBox(c).apply {
                buttonTintList = ColorStateList.valueOf(0xFF1565C0.toInt())
                visibility = View.GONE
            }
            root.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(4) })
            root.addView(tf, LinearLayout.LayoutParams(c.dp(104), c.dp(70)))

            // الاسم + التقدم + المعلومات
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(c).apply {
                textSize = 14f
                setTextColor(Color.BLACK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val bar = SegmentBar(c)
            val info = TextView(c).apply { textSize = 12f }
            mid.addView(name)
            mid.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(6)).apply { topMargin = c.dp(6) })
            mid.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(4) })
            root.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = c.dp(10); marginEnd = c.dp(6)
            })

            // الأزرار
            val btnA = c.iconBtn(R.drawable.ic_play, 0xFF37474F.toInt(), 9)
            val btnB = c.iconBtn(R.drawable.ic_close, 0xFF37474F.toInt(), 9)
            root.addView(btnA, LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
            root.addView(btnB, LinearLayout.LayoutParams(c.dp(40), c.dp(40)).apply { marginStart = c.dp(4) })

            val h = VH(root, thumb, dur, name, bar, info, btnA, btnB, check)

            val open = View.OnClickListener {
                val t = h.task
                if (t != null) {
                    if (selecting) toggle(t) else if (t.status == St.DONE) play(t)
                }
            }
            root.setOnClickListener(open)
            check.setOnClickListener { h.task?.let { toggle(it) } }
            root.setOnLongClickListener {
                h.task?.let { startSelect(it) }
                true
            }

            btnA.setOnClickListener {
                val t = h.task ?: return@setOnClickListener
                when (t.status) {
                    St.DONE -> play(t)
                    St.DOWNLOADING, St.QUEUED -> DownloadCenter.pause(t)
                    St.PAUSED, St.FAILED -> DownloadCenter.resume(t)
                }
            }
            btnB.setOnClickListener {
                val t = h.task ?: return@setOnClickListener
                if (t.status == St.DONE) {
                    val pm = PopupMenu(c, btnB)
                    pm.menu.add(0, 1, 0, when (Util.kindOf(t.name)) { "apk" -> "تثبيت"; "video", "audio" -> "تشغيل"; else -> "فتح" })
                    pm.menu.add(0, 2, 1, "مشاركة")
                    pm.menu.add(0, 3, 2, "حذف")
                    pm.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> play(t)
                            2 -> share(t)
                            3 -> confirm("حذف الملف من الجهاز؟") { DownloadCenter.deleteFinished(t) }
                        }
                        true
                    }
                    pm.show()
                } else {
                    showTaskMenu(t, btnB)
                }
            }
            return h
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            bind(holder, data[position])
        }
    }
}
