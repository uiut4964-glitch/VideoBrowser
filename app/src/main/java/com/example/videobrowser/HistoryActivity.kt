package com.example.videobrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** شاشة السجل / الإشارات المرجعية: صفوف منفصلة، حذف سطر واحد، بحث، وفتح الرابط */
class HistoryActivity : AppCompatActivity() {
    private lateinit var key: String
    private var all: MutableList<Store.Entry> = ArrayList()
    private var query = ""
    private var items: List<Any> = emptyList()
    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private val adapter = Adapter()

    private class VH(val root: View) : RecyclerView.ViewHolder(root) {
        var title: TextView? = null
        var url: TextView? = null
        var letter: TextView? = null
        var del: ImageButton? = null
        var entry: Store.Entry? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra("key") ?: "history"
        val isHist = key == "history"
        val dark = 0xFF37474F.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        header.addView(iconBtn(R.drawable.ic_back, dark).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = if (isHist) "السجل" else "الإشارات المرجعية"
            textSize = 19f
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        header.addView(iconBtn(R.drawable.ic_delete, dark).apply {
            setOnClickListener {
                if (all.isEmpty()) return@setOnClickListener
                AlertDialog.Builder(this@HistoryActivity)
                    .setMessage(if (isHist) "مسح كل السجل؟" else "حذف كل الإشارات المرجعية؟")
                    .setPositiveButton("نعم") { _, _ ->
                        all.clear()
                        save()
                        rebuild()
                    }
                    .setNegativeButton("لا", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // مربع البحث
        val sb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFF1F3F4.toInt(), 24)
            setPadding(dp(14), 0, dp(14), 0)
        }
        sb.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(0xFF78909C.toInt())
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val et = EditText(this).apply {
            hint = if (isHist) "بحث في السجل" else "بحث في الإشارات"
            textSize = 14f
            setSingleLine()
            background = null
            setPadding(dp(10), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    rebuild()
                }
            })
        }
        sb.addView(et, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(12), dp(4), dp(12), dp(8))
        })

        val frame = FrameLayout(this)
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
            itemAnimator = null
        }
        val dv = DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        dv.setDrawable(GradientDrawable().apply { setColor(0xFFE8EAED.toInt()); setSize(1, dp(1)) })
        rv.addItemDecoration(dv)
        empty = TextView(this).apply {
            text = if (isHist) "السجل فارغ" else "لا توجد إشارات مرجعية"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
        }
        frame.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        all = Store.entries(this, key)
        rebuild()
    }

    private fun save() = Store.putEntries(this, key, all)

    private fun dayLabel(t: Long): String {
        if (t <= 0) return "أقدم"
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val today = c.timeInMillis
        return when {
            t >= today -> "اليوم"
            t >= today - 86400000L -> "أمس"
            else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(t))
        }
    }

    private fun rebuild() {
        val q = query.lowercase()
        val list = ArrayList<Any>()
        var last = ""
        for (e in all) {
            if (q.isNotEmpty() && !e.title.lowercase().contains(q) && !e.url.lowercase().contains(q)) continue
            val d = dayLabel(e.time)
            if (d != last) { list.add(d); last = d }
            list.add(e)
        }
        items = list
        adapter.notifyDataSetChanged()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEntry(e: Store.Entry, newTab: Boolean) {
        setResult(RESULT_OK, Intent().putExtra("url", e.url).putExtra("newTab", newTab))
        finish()
    }

    private fun remove(e: Store.Entry) {
        all.remove(e)
        save()
        rebuild()
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount(): Int = items.size
        override fun getItemViewType(position: Int): Int = if (items[position] is String) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = this@HistoryActivity
            if (viewType == 0) {
                val tv = TextView(c).apply {
                    textSize = 14f
                    setTextColor(0xFF1565C0.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(6))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val h = VH(tv)
                h.title = tv
                return h
            }
            val tvRipple = TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tvRipple, true)
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(16), c.dp(10), c.dp(4), c.dp(10))
                setBackgroundResource(tvRipple.resourceId)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val letter = TextView(c).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(Color.WHITE)
                background = c.circle(0xFF78909C.toInt())
            }
            row.addView(letter, LinearLayout.LayoutParams(c.dp(38), c.dp(38)))
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(c).apply {
                textSize = 15f
                setTextColor(Color.BLACK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val url = TextView(c).apply {
                textSize = 12f
                setTextColor(0xFF78909C.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.START
            }
            col.addView(title)
            col.addView(url)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = c.dp(14); marginEnd = c.dp(6)
            })
            val del = c.iconBtn(R.drawable.ic_close, 0xFF78909C.toInt(), 12)
            row.addView(del, LinearLayout.LayoutParams(c.dp(44), c.dp(44)))
            val h = VH(row)
            h.title = title; h.url = url; h.letter = letter; h.del = del

            row.setOnClickListener { h.entry?.let { openEntry(it, false) } }
            row.setOnLongClickListener {
                val e = h.entry ?: return@setOnLongClickListener true
                val pm = PopupMenu(c, row)
                pm.menu.add(0, 1, 0, "فتح في علامة تبويب جديدة")
                pm.menu.add(0, 2, 1, "نسخ الرابط")
                pm.menu.add(0, 3, 2, "حذف")
                pm.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> openEntry(e, true)
                        2 -> {
                            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("url", e.url))
                            Toast.makeText(c, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                        }
                        3 -> remove(e)
                    }
                    true
                }
                pm.show()
                true
            }
            del.setOnClickListener { h.entry?.let { remove(it) } }
            return h
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val it = items[position]
            if (it is String) {
                holder.title?.text = it
                return
            }
            val e = it as Store.Entry
            holder.entry = e
            holder.title?.text = if (e.title.isBlank()) Util.host(e.url) else e.title
            holder.url?.text = e.url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val host = Util.host(e.url).removePrefix("www.")
            holder.letter?.text = if (host.isEmpty()) "؟" else host.substring(0, 1).uppercase()
        }
    }
}
