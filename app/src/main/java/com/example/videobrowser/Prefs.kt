package com.example.videobrowser

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val engines = linkedMapOf(
        "Google" to "https://www.google.com/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q="
    )

    fun folder(c: Context): String? = sp(c).getString("folder", null)
    fun setFolder(c: Context, v: String?) { sp(c).edit().putString("folder", v).apply() }

    fun parts(c: Context): Int = sp(c).getInt("parts", 4)
    fun setParts(c: Context, v: Int) { sp(c).edit().putInt("parts", v).apply() }

    fun maxConcurrent(c: Context): Int = sp(c).getInt("maxc", 2)
    fun setMaxConcurrent(c: Context, v: Int) { sp(c).edit().putInt("maxc", v).apply() }

    fun home(c: Context): String = sp(c).getString("home", "https://www.google.com") ?: "https://www.google.com"
    fun setHome(c: Context, v: String) { sp(c).edit().putString("home", v).apply() }

    fun engine(c: Context): String = sp(c).getString("engine", "Google") ?: "Google"
    fun setEngine(c: Context, v: String) { sp(c).edit().putString("engine", v).apply() }
    fun searchUrl(c: Context): String = engines[engine(c)] ?: "https://www.google.com/search?q="

    fun desktop(c: Context): Boolean = sp(c).getBoolean("desktop", false)
    fun setDesktop(c: Context, v: Boolean) { sp(c).edit().putBoolean("desktop", v).apply() }

    fun wifiOnly(c: Context): Boolean = sp(c).getBoolean("wifi", false)
    fun setWifiOnly(c: Context, v: Boolean) { sp(c).edit().putBoolean("wifi", v).apply() }
}
