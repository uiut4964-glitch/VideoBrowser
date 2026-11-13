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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// يبحث عن الفيديو الذي يُشغَّل فعليًا (الأكبر ظهورًا على الشاشة) ويتجاهل المعاينات
private const val SCAN_JS =
    "(function(){var vs=document.querySelectorAll('video'),best=null,bs=-1,others=[];" +
        "var vw=window.innerWidth,vh=window.innerHeight;" +
        "for(var i=0;i<vs.length;i++){var v=vs[i];if(!v.__vid)v.__vid=Math.random().toString(36).slice(2);" +
        "var s0=v.currentSrc||v.src||'';var r=v.getBoundingClientRect();" +
        "var w=Math.max(0,Math.min(r.right,vw)-Math.max(r.left,0));" +
        "var h=Math.max(0,Math.min(r.bottom,vh)-Math.max(r.top,0));var area=w*h;" +
        "var playing=!v.paused&&!v.ended&&v.readyState>2;" +
        "if(area<1||(!playing&&!(v.currentTime>1))){if(s0)others.push(s0);continue;}" +
        "var sc=area*(playing?4:1)*(v.muted?1:2);" +
        "if(sc>bs){if(best&&best.src)others.push(best.src);bs=sc;best={id:v.__vid,src:s0,poster:v.poster||''};}" +
        "else if(s0)others.push(s0);}" +
        "var og=document.querySelector('meta[property=\"og:image\"]');" +
        "return JSON.stringify({n:vs.length,a:best,o:others,og:og?og.content:''});})()"

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
        var pending = false
        val media = LinkedHashMap<String, MediaItem>()
        val sniffed = LinkedHashMap<String, Long>()
        var sawBlob = false
        var activeId: String? = null
        var playStart = 0L
        var poster = ""
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
    private lateinit var backItem: View
    private lateinit var fwdItem: View
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var desktop = false
    private lateinit var defaultUa: String
    private var pathView: TextView? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
            Prefs.setFolder(this, uri.toString())
            pathView?.text = pathText()
        }
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            cur?.let { scan(it) }
            ui.postDelayed(this, 1000)
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
        restoreTabs(intent?.data?.toString())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) { hideCustom(); return }
                val t = cur
                if (t != null && t.web.canGoBack()) t.web.goBack()
                else if (t != null && tabs.size > 1) closeTab(t)
                else finish()
            }
        })
        showLastCrash()
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
        saveTabs()
        super.onPause()
    }

    override fun onStop() {
        saveTabs()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        content.removeAllViews()
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- حفظ واسترجاع التبويبات
    private fun saveTabs() {
        val kept = tabs.filter { it.url.startsWith("http") }
        if (kept.isEmpty()) return
        Store.put(this, "tabs", kept.map { it.title to it.url })
        getSharedPreferences("state", Context.MODE_PRIVATE).edit()
            .putInt("cur", kept.indexOf(cur).coerceAtLeast(0)).apply()
    }

    private fun restoreTabs(start: String?) {
        val saved = Store.get(this, "tabs")
        if (saved.isEmpty()) {
            newTab(start ?: Prefs.home(this))
            return
        }
        val idx = getSharedPreferences("state", Context.MODE_PRIVATE).getInt("cur", 0)
        for (p in saved) newTab(p.second, false, p.first)
        selectTab(tabs[idx.coerceIn(0, tabs.size - 1)])
        if (start != null) newTab(start)
    }

    private fun showLastCrash() {
        val f = File(filesDir, "crash.txt")
        if (!f.exists()) return
        val txt = try { f.readText() } catch (e: Exception) { return }
        val sv = ScrollView(this)
        sv.addView(TextView(this).apply { text = txt; textSize = 10f; setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true) })
        AlertDialog.Builder(this)
            .setTitle("حدث خطأ في التشغيل السابق")
            .setView(sv)
            .setPositiveButton("نسخ") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("crash", txt))
                f.delete()
            }
            .setNegativeButton("إغلاق") { _, _ -> f.delete() }
            .show()
    }

    // ---------------------------------------------------------------- واجهة المستخدم
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val dark = 0xFF455A64.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setBackgroundColor(0xFFF1F3F4.toInt())
        }
        tabBtn = TextView(this).apply {
            text = "1"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(dark)
            background = rounded(Color.WHITE, 6, 0xFF607D8B.toInt())
            setOnClickListener { showTabs() }
        }
        top.addView(tabBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
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
        top.addView(urlBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8); marginEnd = dp(2) })
        top.addView(iconBtn(R.drawable.ic_refresh, dark, 9).apply { setOnClickListener { cur?.web?.reload() } },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        top.addView(iconBtn(R.drawable.ic_more, dark, 8).apply { setOnClickListener { showMenu(it) } },
            LinearLayout.LayoutParams(dp(40), dp(40)))
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

        // الشريط السفلي
        root.addView(View(this).apply { setBackgroundColor(0xFFDADCE0.toInt()) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        fun nav(res: Int, label: String, onClick: () -> Unit): View {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(ripple())
                setOnClickListener { onClick() }
            }
            c.addView(ImageView(this).apply { setImageResource(res); setColorFilter(dark) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            c.addView(TextView(this).apply {
                text = label; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(dark)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            bottom.addView(c, LinearLayout.LayoutParams(0, dp(54), 1f))
            return c
        }
        backItem = nav(R.drawable.ic_back, "رجوع") { val t = cur; if (t != null && t.web.canGoBack()) t.web.goBack() }
        fwdItem = nav(R.drawable.ic_forward, "أمام") { val t = cur; if (t != null && t.web.canGoForward()) t.web.goForward() }
        nav(R.drawable.ic_home, "الرئيسية") { cur?.web?.loadUrl(Prefs.home(this)) }
        nav(R.drawable.ic_tabs, "التبويبات") { showTabs() }
        nav(R.drawable.ic_download, "التحميلات") { startActivity(Intent(this, DownloadsActivity::class.java)) }
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        return root
    }

    private fun updateNav() {
        val t = cur
        backItem.alpha = if (t != null && t.web.canGoBack()) 1f else 0.35f
        fwdItem.alpha = if (t != null && t.web.canGoForward()) 1f else 0.35f
    }

    private fun updateFab() {
        val n = cur?.media?.size ?: 0
        (fabIcon.background as GradientDrawable).setColor(if (n > 0) 0xFFE53935.toInt() else 0xFF78909C.toInt())
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        badge.text = n.toString()
    }

    // ---------------------------------------------------------------- التبويبات
    private fun tabOf(w: WebView): Tab? = tabs.firstOrNull { it.web === w }

    private fun newTab(url: String?, load: Boolean = true, title: String = "") {
        val w = WebView(this)
        setupWebView(w)
        val t = Tab(w)
        tabs.add(t)
        if (load) {
            selectTab(t)
            if (url != null) w.loadUrl(url)
        } else {
            t.url = url ?: ""
            if (title.isNotEmpty()) t.title = title
            t.pending = true
        }
        tabBtn.text = tabs.size.toString()
    }

    private fun selectTab(t: Tab) {
        cur?.let { content.removeView(it.web); it.web.onPause() }
        cur = t
        t.web.onResume()
        content.addView(t.web, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        if (t.pending) {
            t.pending = false
            t.web.loadUrl(t.url)
        }
        urlBar.setText(t.url)
        tabBtn.text = tabs.size.toString()
        progress.visibility = View.GONE
        updateFab()
        updateNav()
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
                t.sniffed.clear()
                t.activeId = null
                t.sawBlob = false
                t.poster = ""
                if (t === cur) { urlBar.setText(url); updateFab(); updateNav() }
            }

            override fun onPageFinished(v: WebView, url: String) {
                val t = tabOf(v) ?: return
                t.url = url
                t.title = v.title ?: url
                addHistory(t.title, url)
                if (t === cur) updateNav()
                scan(t)
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (MEDIA_REGEX.containsMatchIn(u) || u.contains("videoplayback")) {
                    ui.post { tabOf(v)?.let { rec(it, u) } }
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

        w.setDownloadListener { url, _, cd, mime, len ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                toast("هذا النوع من الروابط لا يمكن تحميله مباشرة")
            } else {
                val fname = URLUtil.guessFileName(url, cd, mime)
                val ext = fname.substringAfterLast('.', "bin")
                val t = cur
                val f = Format("الملف", "", url, "direct", ext, false, if (len > 0) Util.fmtSize(len) else "")
                showFormatSheet(listOf(f), if (t != null) headersFor(t, w) else emptyMap(), fname.substringBeforeLast('.'), "")
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

    // ---------------------------------------------------------------- كشف الفيديو الذي يُشغَّل فعليًا
    private fun rec(t: Tab, url: String) {
        if (t.sniffed.containsKey(url)) return
        t.sniffed[url] = System.currentTimeMillis()
        if (t.sniffed.size > 80) t.sniffed.remove(t.sniffed.keys.first())
    }

    private fun addTo(t: Tab, url: String) {
        t.media[url] = MediaItem(url, if (url.contains(".m3u8", true)) "hls" else "direct")
    }

    private fun scan(t: Tab) {
        if (t.pending) return
        t.web.evaluateJavascript(SCAN_JS) { r ->
            try {
                if (r != null && r != "null") {
                    val s = JSONTokener(r).nextValue() as? String
                    if (s != null) onScan(t, JSONObject(s))
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun onScan(t: Tab, o: JSONObject) {
        val n = o.optInt("n")
        val a = o.optJSONObject("a")
        val others = HashSet<String>()
        val oa = o.optJSONArray("o")
        if (oa != null) for (i in 0 until oa.length()) others.add(oa.getString(i))

        if (a == null) {
            t.activeId = null
            t.media.clear()
            if (n == 0) {
                // لا يوجد عنصر فيديو في الصفحة نفسها (قد يكون داخل iframe): نعرض آخر ما التُقط حديثًا
                val now = System.currentTimeMillis()
                for ((u, ts) in t.sniffed) if (now - ts < 90000) addTo(t, u)
            }
            updateFab()
            return
        }
        val id = a.getString("id")
        if (id != t.activeId) {
            t.activeId = id
            t.playStart = System.currentTimeMillis()
        }
        val src = a.optString("src")
        t.poster = a.optString("poster").ifEmpty { o.optString("og") }
        t.media.clear()
        if (src.startsWith("http")) {
            addTo(t, src)
        } else {
            if (src.startsWith("blob:")) t.sawBlob = true
            val cand = t.sniffed.filter { !others.contains(it.key) }
            val recent = cand.filter { it.value >= t.playStart - 5000 }
            for (u in (if (recent.isNotEmpty()) recent else cand).keys) addTo(t, u)
        }
        updateFab()
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
                if (t.sawBlob) "هذا الموقع يستخدم بثًا محميًا لا يمكن تحميله مباشرة"
                else "لم أجد فيديو قيد التشغيل. شغّل الفيديو الذي تريده أولًا ثم اضغط الزر"
            )
            return
        }
        toast("جارٍ جلب الصيغ المتاحة...")
        val items = t.media.values.toList()
        val headers = headersFor(t)
        val title = if (t.title.isBlank()) Util.host(t.url) else t.title
        val poster = t.poster
        io.execute {
            val list = ArrayList<Format>()
            for (item in items) {
                try { list.addAll(Formats.resolve(item, headers)) } catch (e: Exception) {}
            }
            val clean = list.filter { f -> f.fromMaster || list.none { it.fromMaster && it.url == f.url } }
                .distinctBy { it.url }
            ui.post {
                if (isFinishing) return@post
                if (clean.isEmpty()) toast("تعذر جلب معلومات الفيديو") else showFormatSheet(clean, headers, title, poster)
            }
        }
    }

    // ---------------------------------------------------------------- شاشة اختيار الجودة
    private fun pathText(): String {
        val f = Prefs.folder(this)
        return "المسار: " + (if (f == null) "Download/VideoBrowser" else (Uri.parse(f).lastPathSegment ?: f))
    }

    private fun showFormatSheet(list: List<Format>, headers: Map<String, String>, title: String, poster: String) {
        val dlg = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // الرأس: صورة مصغرة + اسم الملف
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_video)
            setColorFilter(0xFF90A4AE.toInt())
            background = rounded(0xFFECEFF1.toInt(), 8)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        val et = EditText(this).apply {
            setText(title)
            textSize = 14f
            maxLines = 3
        }
        head.addView(thumb, LinearLayout.LayoutParams(dp(110), dp(70)))
        head.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        root.addView(head)
        if (poster.startsWith("http")) {
            ThumbLoader.loadUrl(poster, headers) { b ->
                if (b != null) {
                    thumb.clearColorFilter()
                    thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    thumb.setImageBitmap(b)
                }
            }
        }

        // المسار
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pv = TextView(this).apply {
            text = pathText(); textSize = 12f; setTextColor(0xFF607D8B.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pathView = pv
        prow.addView(pv, LinearLayout.LayoutParams(0, WRAP, 1f))
        prow.addView(TextView(this).apply {
            text = "تغيير"; textSize = 13f; setTextColor(0xFF1565C0.toInt())
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener { pickFolder.launch(null) }
        })
        root.addView(prow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        root.addView(View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()) }, LinearLayout.LayoutParams(MATCH, dp(1)))

        // قائمة الصيغ
        var selected: Format? = null
        val boxes = ArrayList<Pair<RadioButton, Format>>()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var rows = 0
        var sections = 0
        fun section(text: String, color: Int) {
            sections++
            box.addView(TextView(this).apply {
                this.text = text; textSize = 14f; setTextColor(color)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(12), dp(4), dp(4))
            })
        }
        fun row(f: Format) {
            rows++
            val rb = RadioButton(this).apply { isClickable = false; isFocusable = false }
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(6), dp(2), dp(6))
                setBackgroundResource(ripple())
            }
            r.addView(rb)
            r.addView(TextView(this).apply { text = f.label; textSize = 15f; setTextColor(Color.BLACK) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
            r.addView(TextView(this).apply {
                text = if (f.kind == "hls") "HLS" else f.ext.uppercase()
                textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF607D8B.toInt())
            }, LinearLayout.LayoutParams(dp(56), WRAP))
            r.addView(TextView(this).apply {
                text = f.sizeText; textSize = 13f; gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setTextColor(0xFF607D8B.toInt())
            }, LinearLayout.LayoutParams(dp(92), WRAP))
            r.setOnClickListener {
                selected = f
                boxes.forEach { it.first.isChecked = it.second === f }
            }
            boxes.add(rb to f)
            box.addView(r)
        }
        val video = list.filter { !Formats.isAudio(it.ext) }
        val audio = list.filter { Formats.isAudio(it.ext) }
        if (video.isNotEmpty()) { section("الفيديو", 0xFFE53935.toInt()); video.forEach { row(it) } }
        if (audio.isNotEmpty()) { section("الصوت", 0xFF8E24AA.toInt()); audio.forEach { row(it) } }
        boxes.firstOrNull()?.let { it.first.isChecked = true; selected = it.second }

        val sv = ScrollView(this)
        sv.addView(box)
        val maxH = (resources.displayMetrics.heightPixels * 0.42).toInt()
        val wantH = rows * dp(50) + sections * dp(40) + dp(8)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, minOf(wantH, maxH)))

        // زر التحميل
        val btn = TextView(this).apply {
            text = "تحميل"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(0xFFE53935.toInt(), 8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_download, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                val f = selected
                if (f != null) {
                    dlg.dismiss()
                    startDownload(f, et.text.toString(), headers, poster)
                }
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        dlg.setContentView(root)
        dlg.setOnDismissListener { pathView = null }
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }

    private fun startDownload(f: Format, n: String, headers: Map<String, String>, poster: String) {
        var name = Util.safeName(n)
        if (!name.contains('.') || name.substringAfterLast('.').length > 5) name += "." + f.ext
        val t = Task(UUID.randomUUID().toString(), f.url, name, f.kind, headers)
        t.mime = Util.mimeOf(name)
        t.thumbUrl = poster
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
