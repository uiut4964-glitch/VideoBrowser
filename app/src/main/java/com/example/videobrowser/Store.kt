package com.example.videobrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Store {
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
