package com.example.videobrowser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private const val SCAN_JS =
    "(function(){var r=[];document.querySelectorAll('video,audio,source').forEach(function(e){" +
        "var s=e.currentSrc||e.src;if(s)r.push(s);});return JSON.stringify(r);})()"

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val MEDIA_REGEX = Regex(
    "\\.(mp4|m4v|webm|mkv|mov|flv|3gp|m3u8|mp3|m4a|aac|ogg|wav)(\\?|#|$)",
    RegexOption.IGNORE_CASE
)

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView) {
        var title = "علامة تبويب جديدة"
        var url = ""
        val media = LinkedHashMap<String, MediaItem>()
        var sawBlob = false
    }

    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private lateinit var content: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fab: FrameLayout
    private lateinit var fabIcon: ImageView
    private lateinit var badge: TextView
    private lateinit var tabBtn: TextView
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var desktop = false
    private lateinit var defaultUa: String

    private val scanLoop = object : Runnable {
        override fun run() {
            cur?.let { scan(it) }
            ui.postDelayed(this, 2500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        defaultUa = WebSettings.getDefaultUserAgent(this)
        desktop = Prefs.desktop(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(buildUi())
        CookieManager.getInstance().setAcceptCookie(true)
        newTab(intent?.data?.toString() ?: Prefs.home(this))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) { hideCustom(); return }
                val t = cur
                if (t != null && t.web.canGoBack()) t.web.goBack()
                else if (t != null && tabs.size > 1) closeTab(t)
                else finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.data?.toString()
        if (u != null) newTab(u)
    }

    override fun onResume() {
        super.onResume()
        ui.post(scanLoop)
    }

    override fun onPause() {
        ui.removeCallbacks(scanLoop)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- واجهة المستخدم
    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun barButton(text: String, onClick: (View) -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(0xFF37474F.toInt())
        setOnClickListener { onClick(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFFF1F3F4.toInt())
        }
        tabBtn = TextView(this).apply {
            text = "1"
            gravity = Gravity.CENTER
            setTextColor(0xFF37474F.toInt())
            background = rounded(Color.WHITE, 6, 0xFF607D8B.toInt())
            setOnClickListener { showTabs() }
        }
        top.addView(tabBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

        urlBar = EditText(this).apply {
            hint = "ابحث أو اكتب رابط URL"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = rounded(Color.WHITE, 22)
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { v, _, _ ->
                go((v as EditText).text.toString())
                true
            }
        }
        top.addView(urlBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(8); marginEnd = dp(4)
        })
        top.addView(barButton("⟳") { cur?.web?.reload() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        top.addView(barButton("⋮") { showMenu(it) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH, dp(3)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // الزر العائم
        fab = FrameLayout(this)
        fabIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_download)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = circle(0xFF78909C.toInt())
            elevation = dp(6).toFloat()
        }
        fab.addView(fabIcon, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        badge = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFE53935.toInt())
            background = circle(Color.WHITE)
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        fab.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
        content.addView(fab, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(14), dp(14), dp(14), dp(24))
        })
        fab.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = v.x - e.rawX; dy = v.y - e.rawY; sx = e.rawX; sy = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - sx) > dp(6) || abs(e.rawY - sy) > dp(6)) moved = true
                        if (moved) {
                            v.x = (e.rawX + dx).coerceIn(0f, (content.width - v.width).toFloat())
                            v.y = (e.rawY + dy).coerceIn(0f, (content.height - v.height).toFloat())
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onFabClick()
                }
                return true
            }
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFF1F3F4.toInt())
        }
        fun item(text: String, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF37474F.toInt())
                setOnClickListener { onClick() }
            }
            bottom.addView(tv, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        item("رجوع") { val t = cur; if (t != null && t.web.canGoBack()) t.web.goBack() }
        item("أمام") { val t = cur; if (t != null && t.web.canGoForward()) t.web.goForward() }
        item("الرئيسية") { cur?.web?.loadUrl(Prefs.home(this)) }
        item("التحميلات") { startActivity(Intent(this, DownloadsActivity::class.java)) }
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        return root
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun updateFab() {
        val n = cur?.media?.size ?: 0
        (fabIcon.background as GradientDrawable).setColor(if (n > 0) 0xFFE53935.toInt() else 0xFF78909C.toInt())
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        badge.text = n.toString()
    }

    // ---------------------------------------------------------------- التبويبات
    private fun tabOf(w: WebView): Tab? = tabs.firstOrNull { it.web === w }

    private fun newTab(url: String?) {
        val w = WebView(this)
        setupWebView(w)
        val t = Tab(w)
        tabs.add(t)
        selectTab(t)
        if (url != null) w.loadUrl(url)
    }

    private fun selectTab(t: Tab) {
        cur?.let { content.removeView(it.web); it.web.onPause() }
        cur = t
        t.web.onResume()
        content.addView(t.web, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        urlBar.setText(t.url)
        tabBtn.text = tabs.size.toString()
        progress.visibility = View.GONE
        updateFab()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        tabs.remove(t)
        content.removeView(t.web)
        t.web.destroy()
        if (cur === t) cur = null
        if (tabs.isEmpty()) newTab(Prefs.home(this)) else if (cur == null) selectTab(tabs[minOf(i, tabs.size - 1)])
        tabBtn.text = tabs.size.toString()
    }

    private fun showTabs() {
        val names = tabs.mapIndexed { i, t -> "${i + 1}. " + t.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("علامات التبويب")
            .setItems(names) { _, i -> selectTab(tabs[i]) }
            .setPositiveButton("علامة جديدة") { _, _ -> newTab(Prefs.home(this)) }
            .setNeutralButton("إغلاق الحالية") { _, _ -> cur?.let { closeTab(it) } }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun go(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(" ") || !s.contains(".") -> Prefs.searchUrl(this) + URLEncoder.encode(s, "UTF-8")
            else -> "https://$s"
        }
        cur?.web?.loadUrl(url)
        urlBar.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    // ---------------------------------------------------------------- WebView
    private fun applyUa(w: WebView) {
        w.settings.userAgentString = if (desktop) DESKTOP_UA else defaultUa
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.javaScriptCanOpenWindowsAutomatically = false
        applyUa(w)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                return !(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:") ||
                    u.startsWith("data:") || u.startsWith("blob:"))
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                val t = tabOf(v) ?: return
                t.url = url
                t.media.clear()
                t.sawBlob = false
                if (t === cur) { urlBar.setText(url); updateFab() }
            }

            override fun onPageFinished(v: WebView, url: String) {
                val t = tabOf(v) ?: return
                t.title = v.title ?: url
                addHistory(t.title, url)
                scan(t)
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (MEDIA_REGEX.containsMatchIn(u) || u.contains("videoplayback")) {
                    ui.post { tabOf(v)?.let { addMedia(it, u, false) } }
                }
                return null
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (tabOf(v) === cur) {
                    progress.progress = p
                    progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                if (title != null) tabOf(v)?.title = title
            }

            override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = view
                customCb = cb
                view.setBackgroundColor(Color.BLACK)
                (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(MATCH, MATCH))
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() { hideCustom() }
        }

        w.setDownloadListener { url, _, cd, mime, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                toast("هذا النوع من الروابط لا يمكن تحميله مباشرة")
            } else {
                val fname = URLUtil.guessFileName(url, cd, mime)
                val ext = fname.substringAfterLast('.', "bin")
                val t = cur
                askName(Format("", "", url, "direct", ext), if (t != null) headersFor(t, w) else emptyMap(), fname.substringBeforeLast('.'))
            }
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun addHistory(title: String, url: String) {
        if (!url.startsWith("http")) return
        val l = Store.get(this, "history")
        l.removeAll { it.second == url }
        l.add(0, title to url)
        Store.put(this, "history", l)
    }

    // ---------------------------------------------------------------- كشف الفيديو
    private fun scan(t: Tab) {
        t.web.evaluateJavascript(SCAN_JS) { r ->
            try {
                if (r != null && r != "null") {
                    val s = JSONTokener(r).nextValue() as? String
                    if (s != null) {
                        val arr = JSONArray(s)
                        for (i in 0 until arr.length()) {
                            val u = arr.getString(i)
                            if (u.startsWith("blob:")) t.sawBlob = true
                            else if (u.startsWith("http")) addMedia(t, u, true)
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun addMedia(t: Tab, url: String, fromDom: Boolean) {
        if (t.media.containsKey(url)) return
        if (!fromDom && !MEDIA_REGEX.containsMatchIn(url) && !url.contains("videoplayback")) return
        val type = if (url.contains(".m3u8", true)) "hls" else "direct"
        t.media[url] = MediaItem(url, type)
        if (t === cur) updateFab()
    }

    private fun headersFor(t: Tab, w: WebView = t.web): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["User-Agent"] = w.settings.userAgentString
        if (t.url.startsWith("http")) {
            m["Referer"] = t.url
            try {
                val u = URL(t.url)
                m["Origin"] = u.protocol + "://" + u.host
            } catch (e: Exception) {
            }
        }
        return m
    }

    private fun onFabClick() {
        val t = cur ?: return
        if (t.media.isEmpty()) {
            toast(
                if (t.sawBlob) "هذا الموقع يستخدم بثًا محميًا (blob) لا يمكن تحميله مباشرة"
                else "لم يتم العثور على فيديو في هذه الصفحة. شغّل الفيديو أولًا ثم حاول مرة أخرى"
            )
            return
        }
        toast("جارٍ جلب الصيغ المتاحة...")
        val items = t.media.values.toList()
        val headers = headersFor(t)
        val title = t.title
        io.execute {
            val list = ArrayList<Format>()
            for (item in items) {
                try { list.addAll(Formats.resolve(item, headers)) } catch (e: Exception) {}
            }
            val clean = list.filter { f -> f.fromMaster || list.none { it.fromMaster && it.url == f.url } }
                .distinctBy { it.url }
            ui.post {
                if (isFinishing) return@post
                if (clean.isEmpty()) toast("تعذر جلب معلومات الفيديو") else showFormats(clean, headers, title)
            }
        }
    }

    private fun showFormats(list: List<Format>, headers: Map<String, String>, title: String) {
        val ad = object : ArrayAdapter<Format>(this, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val f = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = f.label
                v.findViewById<TextView>(android.R.id.text2).text = f.details
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle("اختر جودة التحميل")
            .setAdapter(ad) { _, which -> askName(list[which], headers, title) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun askName(f: Format, headers: Map<String, String>, title: String) {
        val et = EditText(this).apply { setText(Util.safeName(title) + "." + f.ext) }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("اسم الملف")
            .setView(box)
            .setPositiveButton("تحميل") { _, _ -> startDownload(f, et.text.toString(), headers) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startDownload(f: Format, n: String, headers: Map<String, String>) {
        var name = Util.safeName(n)
        if (!name.contains('.')) name += "." + f.ext
        val t = Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers)
        t.mime = Util.mimeOf(name)
        DownloadCenter.add(t)
        toast("بدأ التحميل — تابعه من زر (التحميلات)")
    }

    // ---------------------------------------------------------------- القائمة
    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "علامة تبويب جديدة")
        m.add(0, 2, 1, "مشاركة")
        m.add(0, 3, 2, "السجل")
        m.add(0, 4, 3, "نسخ الرابط")
        m.add(0, 5, 4, "العلامات المرجعية")
        m.add(0, 6, 5, "إضافة علامة مرجعية")
        m.add(0, 7, 6, "وضع سطح المكتب").isCheckable = true
        m.add(0, 8, 7, "الإعدادات")
        m.findItem(7).isChecked = desktop
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(Prefs.home(this))
                2 -> startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, cur?.url ?: ""),
                        "مشاركة"
                    )
                )
                3 -> showSaved("السجل", "history")
                4 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", cur?.url ?: ""))
                    toast("تم نسخ الرابط")
                }
                5 -> showSaved("العلامات المرجعية", "bookmarks")
                6 -> {
                    val t = cur
                    if (t != null && t.url.startsWith("http")) {
                        val l = Store.get(this, "bookmarks")
                        l.removeAll { it.second == t.url }
                        l.add(0, t.title to t.url)
                        Store.put(this, "bookmarks", l)
                        toast("تمت الإضافة إلى العلامات المرجعية")
                    }
                }
                7 -> {
                    desktop = !desktop
                    tabs.forEach { applyUa(it.web) }
                    cur?.web?.reload()
                }
                8 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        pm.show()
    }

    private fun showSaved(title: String, key: String) {
        val list = Store.get(this, key)
        if (list.isEmpty()) { toast("القائمة فارغة"); return }
        val names = list.map { if (it.first.isEmpty()) it.second else it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, i -> cur?.web?.loadUrl(list[i].second) }
            .setNeutralButton("مسح الكل") { _, _ -> Store.put(this, key, emptyList()) }
            .setNegativeButton("إغلاق", null)
            .show()
    }
}
