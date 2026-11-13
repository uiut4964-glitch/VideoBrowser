package com.example.videobrowser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
    private val adapter = Adapter()
    private val listener: () -> Unit = { refresh() }

    private inner class VH(
        val root: LinearLayout, val name: TextView, val bar: ProgressBar,
        val info: TextView, val b1: TextView, val b2: TextView, val b3: TextView
    ) : RecyclerView.ViewHolder(root) {
        var task: Task? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "التحميلات"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(14), dp(16), dp(10))
            setBackgroundColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE) }
        fun mk(text: String, m: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { mode = m; refresh() }
        }
        tab0 = mk("قيد التحميل", 0)
        tab1 = mk("المنتهية", 1)
        tabs.addView(tab0, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(tab1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val frame = android.widget.FrameLayout(this)
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            adapter = this@DownloadsActivity.adapter
            itemAnimator = null
        }
        empty = TextView(this).apply {
            text = "لا توجد عناصر"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
        }
        frame.addView(rv, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(empty, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        adapter.data = DownloadCenter.tasks.filter { (it.status == St.DONE) == (mode == 1) }
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.data.isEmpty()) View.VISIBLE else View.GONE
        tab0.setTextColor(if (mode == 0) 0xFF1565C0.toInt() else 0xFF777777.toInt())
        tab1.setTextColor(if (mode == 1) 0xFF1565C0.toInt() else 0xFF777777.toInt())
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
        val sp = if (t.status == St.DOWNLOADING && t.speed > 0) " • " + Util.fmtSpeed(t.speed) else ""
        return st + " • " + size + sp
    }

    private fun bind(h: VH, t: Task) {
        h.task = t
        h.name.text = t.name
        if (t.status == St.DONE) {
            h.bar.visibility = View.GONE
            h.info.text = Util.fmtSize(t.total)
            h.b1.text = "تشغيل"; h.b1.visibility = View.VISIBLE
            h.b2.text = "مشاركة"
            h.b3.text = "حذف"; h.b3.visibility = View.VISIBLE
        } else {
            h.bar.visibility = View.VISIBLE
            h.bar.progress = t.permille()
            h.info.text = infoText(t)
            h.b1.text = when (t.status) {
                St.PAUSED -> "استئناف"
                St.FAILED -> "إعادة المحاولة"
                else -> "إيقاف مؤقت"
            }
            h.b1.visibility = if (t.status == St.MERGING) View.GONE else View.VISIBLE
            h.b2.text = "إلغاء"
            h.b3.visibility = View.GONE
        }
    }

    private fun play(t: Task) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(t.savedUri), t.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "لا يوجد تطبيق لتشغيل هذا الملف", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الملف (ربما تم حذفه)", Toast.LENGTH_LONG).show()
        }
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
                orientation = LinearLayout.VERTICAL
                setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = c.dp(10).toFloat()
                    setColor(Color.WHITE)
                    setStroke(c.dp(1), 0xFFE0E0E0.toInt())
                }
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(c.dp(10), c.dp(6), c.dp(10), c.dp(6)) }
            }
            val name = TextView(c).apply {
                textSize = 15f
                setTextColor(Color.BLACK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            val info = TextView(c).apply { textSize = 12f; setTextColor(0xFF666666.toInt()) }
            val btns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(): TextView = TextView(c).apply {
                textSize = 14f
                setTextColor(0xFF1565C0.toInt())
                setPadding(c.dp(10), c.dp(10), c.dp(10), c.dp(10))
            }
            val b1 = btn(); val b2 = btn(); val b3 = btn()
            btns.addView(b1); btns.addView(b2); btns.addView(b3)
            root.addView(name)
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(8)).apply { topMargin = c.dp(6) })
            root.addView(info)
            root.addView(btns)
            val h = VH(root, name, bar, info, b1, b2, b3)

            b1.setOnClickListener {
                val t = h.task ?: return@setOnClickListener
                when (t.status) {
                    St.DONE -> play(t)
                    St.DOWNLOADING, St.QUEUED -> DownloadCenter.pause(t)
                    St.PAUSED, St.FAILED -> DownloadCenter.resume(t)
                }
            }
            b2.setOnClickListener {
                val t = h.task ?: return@setOnClickListener
                if (t.status == St.DONE) share(t)
                else confirm("إلغاء هذا التحميل وحذف ما تم تحميله؟") { DownloadCenter.cancel(t) }
            }
            b3.setOnClickListener {
                val t = h.task ?: return@setOnClickListener
                confirm("حذف الملف من الجهاز؟") { DownloadCenter.deleteFinished(t) }
            }
            return h
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            bind(holder, data[position])
        }
    }
}
