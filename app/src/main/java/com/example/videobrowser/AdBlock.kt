package com.example.videobrowser

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * حجب نطاقات الإعلانات والتتبع على مستوى الشبكة: الطلب المحجوب يُجاب بردّ فارغ،
 * فلا يصل الإعلان (ولا فيديو الإعلان) إلى الصفحة أصلًا.
 * القائمة: نطاقات معروفة مضمّنة + قائمة عامة تُحدَّث من الإنترنت (Peter Lowe) وتُحفظ محليًا.
 */
object AdBlock {
    private val hosts = HashSet<String>()
    private val pool = Executors.newSingleThreadExecutor()
    private var inited = false

    private const val LIST_URL =
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"

    private val builtin = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "2mdn.net",
        "imasdk.googleapis.com", "moatads.com", "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "openx.net", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "popads.net", "propellerads.com", "mgid.com", "revcontent.com", "teads.tv",
        "tremorhub.com", "spotxchange.com", "springserve.com", "adcolony.com", "applovin.com", "vungle.com",
        "mopub.com", "inmobi.com", "chartboost.com", "adsafeprotected.com", "serving-sys.com",
        "amazon-adsystem.com", "adform.net", "smartadserver.com", "casalemedia.com", "contextweb.com",
        "yieldmo.com", "sharethrough.com", "tsyndicate.com", "realsrv.com", "clickadu.com", "hilltopads.net",
        "adtng.com", "a-ads.com", "ad-maven.com", "admaven.com", "adsterra.com", "popcash.net", "richpush.co",
        "onclickads.net", "bidvertiser.com", "cpmstar.com", "yllix.com", "trafficfactory.biz", "scorecardresearch.com",
        "adskeeper.com", "adsco.re", "adcash.com", "plugrush.com", "eroadvertising.com", "tubecorporate.com",
        "doppiocdn.net", "growcdnssedge.com", "saawsedge.com", "adtechus.com", "advertising.com", "lijit.com",
        "freewheel.tv", "fwmrm.net", "innovid.com", "adition.com", "unityads.unity3d.com", "ironsrc.com", "supersonicads.com"
    )

    private fun file(c: Context) = File(c.filesDir, "adhosts.txt")

    fun init(c: Context) {
        if (inited) return
        inited = true
        synchronized(hosts) { hosts.addAll(builtin) }
        pool.execute {
            loadCached(c)
            val f = file(c)
            if (Prefs.adblock(c) && (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7L * 86400_000L)) {
                try { download(c) } catch (e: Exception) {}
            }
        }
    }

    private fun loadCached(c: Context) {
        try {
            val f = file(c)
            if (!f.exists()) return
            val add = HashSet<String>()
            f.forEachLine { l -> val h = parse(l); if (h != null) add.add(h) }
            synchronized(hosts) { hosts.addAll(add) }
        } catch (e: Exception) {
        }
    }

    private fun parse(line: String): String? {
        var l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        val parts = l.split(Regex("\\s+"))
        l = parts.last().lowercase(Locale.US)
        if (l == "localhost" || l.length < 4 || !l.contains('.')) return null
        if (!l.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        return l
    }

    /** تحديث القائمة من الإنترنت؛ يُرجع عدد النطاقات أو -1 عند الفشل */
    fun download(c: Context): Int {
        val text = Net.fetchText(LIST_URL, mapOf("User-Agent" to "Mozilla/5.0"))
        val add = HashSet<String>()
        text.lineSequence().forEach { val h = parse(it); if (h != null) add.add(h) }
        if (add.size < 500) return -1
        file(c).writeText(add.joinToString("\n"))
        synchronized(hosts) { hosts.addAll(add) }
        return add.size
    }

    fun updateAsync(c: Context, cb: (Int) -> Unit) {
        val app = c.applicationContext
        pool.execute {
            val n = try { download(app) } catch (e: Exception) { -1 }
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(n) }
        }
    }

    fun count(): Int = synchronized(hosts) { hosts.size }

    /** هل هذا النطاق (أو أحد نطاقاته الأب) في القائمة؟ */
    fun isBlocked(host: String?): Boolean {
        if (host == null) return false
        var h = host.lowercase(Locale.US).removePrefix("www.")
        synchronized(hosts) {
            while (h.contains('.')) {
                if (hosts.contains(h)) return true
                h = h.substringAfter('.')
            }
        }
        return false
    }
}
