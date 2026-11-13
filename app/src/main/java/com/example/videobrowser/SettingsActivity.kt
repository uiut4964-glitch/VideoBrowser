package com.example.videobrowser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.provider.Settings
import android.app.ActivityManager
import java.io.File
import java.text.DateFormat
import java.util.Date

class SettingsActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
            Prefs.setFolder(this, uri.toString())
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "الإعدادات"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(14), dp(16), dp(10))
            setBackgroundColor(0xFFF1F3F4.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this)
        sv.addView(box)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    private fun add(title: String, value: String, onClick: () -> Unit) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        r.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(Color.BLACK) })
        r.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(0xFF666666.toInt()) })
        box.addView(r)
        box.addView(View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(options.toTypedArray()) { _, i -> onPick(i); render() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun onOff(b: Boolean) = if (b) "مفعّل" else "معطّل"

    private fun render() {
        box.removeAllViews()

        val f = Prefs.folder(this)
        val folderText = if (f == null) "التنزيلات / VideoBrowser (الافتراضي)"
        else (Uri.parse(f).lastPathSegment ?: f)
        add("مسار التحميل", folderText) {
            choose("مسار التحميل", listOf("اختيار مجلد", "العودة للمسار الافتراضي")) { i ->
                if (i == 0) pick.launch(null) else Prefs.setFolder(this, null)
            }
        }

        val partsOpts = listOf(1, 2, 4, 6, 8, 12, 16)
        add("عدد أجزاء التحميل (اتصالات متوازية)", Prefs.parts(this).toString()) {
            choose("عدد الأجزاء", partsOpts.map { it.toString() }) { i -> Prefs.setParts(this, partsOpts[i]) }
        }

        add("عدد التحميلات في نفس الوقت", Prefs.maxConcurrent(this).toString()) {
            choose("التحميلات المتزامنة", listOf("1", "2", "3", "4", "5")) { i -> Prefs.setMaxConcurrent(this, i + 1) }
        }

        add("الصفحة الرئيسية", Prefs.home(this)) {
            val et = EditText(this).apply { setText(Prefs.home(this@SettingsActivity)); setSingleLine() }
            val fl = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
            AlertDialog.Builder(this).setTitle("الصفحة الرئيسية").setView(fl)
                .setPositiveButton("حفظ") { _, _ ->
                    var u = et.text.toString().trim()
                    if (u.isNotEmpty()) {
                        if (!u.startsWith("http")) u = "https://$u"
                        Prefs.setHome(this, u)
                        render()
                    }
                }
                .setNegativeButton("إلغاء", null).show()
        }

        add("محرك البحث", Prefs.engine(this)) {
            val names = Prefs.engines.keys.toList()
            choose("محرك البحث", names) { i -> Prefs.setEngine(this, names[i]) }
        }

        add("وضع سطح المكتب افتراضيًا", onOff(Prefs.desktop(this))) {
            Prefs.setDesktop(this, !Prefs.desktop(this)); render()
        }

        add("التحميل عبر Wi-Fi فقط", onOff(Prefs.wifiOnly(this))) {
            Prefs.setWifiOnly(this, !Prefs.wifiOnly(this)); render()
        }


        add("العمل في الخلفية", "اسمح للتطبيق بتجاهل توفير البطارية حتى لا يُغلق وهو في الخلفية") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
            }
        }

        add("تشخيص: سبب آخر إغلاق للتطبيق", "اضغط لعرض السبب") { showExitReason() }

        add("مسح السجل", "حذف سجل التصفح") {
            Store.put(this, "history", emptyList())
            Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
        }

        add("مسح ملفات تعريف الارتباط (Cookies)", "تسجيل الخروج من المواقع") {
            CookieManager.getInstance().removeAllCookies(null)
            Toast.makeText(this, "تم المسح", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExitReason() {
        val sb = StringBuilder()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val am = getSystemService(ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 3)
            if (list.isEmpty()) sb.append("لا توجد سجلات إغلاق.\n")
            for (r in list) {
                val why = when (r.reason) {
                    1 -> "أغلق التطبيق نفسه"
                    2 -> "أنهاه النظام (غالبًا توفير الذاكرة أو البطارية)"
                    3 -> "نفاد الذاكرة: أغلقه النظام ليحرر مساحة"
                    4 -> "انهيار بسبب خطأ برمجي"
                    5 -> "انهيار في مكتبة النظام"
                    6 -> "التطبيق لم يعد يستجيب"
                    9 -> "استهلاك مفرط للموارد"
                    10, 11 -> "أغلقه المستخدم"
                    14 -> "تجميد التطبيق لتوفير البطارية"
                    else -> "سبب آخر (" + r.reason + ")"
                }
                sb.append(DateFormat.getDateTimeInstance().format(Date(r.timestamp))).append("\n")
                sb.append(why).append("\n").append(r.description ?: "").append("\n\n")
            }
        } else {
            sb.append("هذه الميزة تحتاج أندرويد 11 أو أحدث.\n")
        }
        val crash = File(filesDir, "crash.txt")
        if (crash.exists()) sb.append("\nآخر خطأ برمجي:\n").append(crash.readText().take(1500))
        AlertDialog.Builder(this).setTitle("سبب آخر إغلاق").setMessage(sb.toString())
            .setPositiveButton("حسنًا", null).show()
    }
}
