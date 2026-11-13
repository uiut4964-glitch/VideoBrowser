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
import android.widget.FrameLayout
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

    private inner class VH(
        val root: LinearLayout, val thumb: ImageView, val dur: TextView, val name: TextView,
        val bar: ProgressBar, val info: TextView, val btnA: ImageButton, val btnB: ImageButton
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
        header.addView(iconBtn(R.drawable.ic_back, dark).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply { text = "التحميلات"; textSize = 19f; setTextColor(Color.BLACK) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE) }
        fun mk(m: Int): Pair<TextView, View> {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { mode = m; refresh() } }
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
        return st + "\n" + size + sp
    }

    private fun bind(h: VH, t: Task) {
        val dark = 0xFF37474F.toInt()
        h.task = t
        h.name.text = t.name

        val th = ThumbLoader.thumbFor(t)
        if (th?.bmp != null) {
            h.thumb.clearColorFilter()
            h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            h.thumb.setImageBitmap(th.bmp)
        } else {
            h.thumb.scaleType = ImageView.ScaleType.CENTER
            h.thumb.setColorFilter(0xFF90A4AE.toInt())
            h.thumb.setImageResource(R.drawable.ic_video)
        }
        ThumbLoader.loadTask(this, t) { refresh() }

        if (t.status == St.DONE) {
            h.bar.visibility = View.GONE
            h.info.text = Util.fmtSize(t.total)
            h.info.setTextColor(0xFF78909C.toInt())
            val d = th?.durMs ?: 0L
            h.dur.visibility = if (d > 0) View.VISIBLE else View.GONE
            if (d > 0) h.dur.text = Util.fmtDur(d / 1000.0)
            h.btnA.setImageResource(R.drawable.ic_play)
            h.btnA.setColorFilter(0xFF1565C0.toInt())
            h.btnA.background = circleStroke()
            h.btnB.setImageResource(R.drawable.ic_more)
            h.btnB.setColorFilter(dark)
        } else {
            h.dur.visibility = View.GONE
            h.bar.visibility = View.VISIBLE
            h.bar.progress = t.permille()
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
            h.btnB.setImageResource(R.drawable.ic_close)
            h.btnB.setColorFilter(0xFFD32F2F.toInt())
        }
    }

    private fun circleStroke() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(dp(1), 0xFFB0BEC5.toInt())
    }

    private fun play(t: Task) {
        val done = DownloadCenter.tasks.filter { it.status == St.DONE && it.savedUri.isNotEmpty() }
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
            root.addView(tf, LinearLayout.LayoutParams(c.dp(104), c.dp(70)))

            // الاسم + التقدم + المعلومات
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(c).apply {
                textSize = 14f
                setTextColor(Color.BLACK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
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

            val h = VH(root, thumb, dur, name, bar, info, btnA, btnB)

            val open = View.OnClickListener {
                val t = h.task
                if (t != null && t.status == St.DONE) play(t)
            }
            root.setOnClickListener(open)

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
                    pm.menu.add(0, 1, 0, "تشغيل")
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
                    confirm("إلغاء هذا التحميل وحذف ما تم تحميله؟") { DownloadCenter.cancel(t) }
                }
            }
            return h
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            bind(holder, data[position])
        }
    }
}
