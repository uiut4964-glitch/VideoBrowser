package com.example.videobrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Store {
    class Entry(val title: String, val url: String, val time: Long)

    fun entries(c: Context, key: String): MutableList<Entry> {
        val a = JSONArray(sp(c).getString(key, "[]") ?: "[]")
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Entry(o.optString("t", ""), o.optString("u", ""), o.optLong("d", 0L))
        }
    }

    fun putEntries(c: Context, key: String, list: List<Entry>) {
        val a = JSONArray()
        list.take(500).forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        sp(c).edit().putString(key, a.toString()).apply()
    }

    private fun sp(c: Context) = c.getSharedPreferences("lists", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): MutableList<Pair<String, String>> {
        val a = JSONArray(sp(c).getString(key, "[]") ?: "[]")
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            o.getString("t") to o.getString("u")
        }
    }

    fun put(c: Context, key: String, list: List<Pair<String, String>>) {
        val a = JSONArray()
        list.take(300).forEach { a.put(JSONObject().put("t", it.first).put("u", it.second)) }
        sp(c).edit().putString(key, a.toString()).apply()
    }
}
