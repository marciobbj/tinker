package com.pdfreader.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

class BookmarkStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

data class Bookmark(
val uri: String,
val title: String = "",
val pageNumber: Int,
val scrollY: Float,
val displayMode: Int, // 0 = vertical, 1 = book
val timestamp: Long = System.currentTimeMillis(),
val totalPages: Int = 1
)

fun save(bookmark: Bookmark) {
try {
val json = JSONObject().apply {
put("uri", bookmark.uri)
put("title", bookmark.title)
put("pageNumber", bookmark.pageNumber)
put("scrollY", bookmark.scrollY)
put("displayMode", bookmark.displayMode)
put("timestamp", bookmark.timestamp)
put("totalPages", bookmark.totalPages)
}
prefs.edit().putString(keyFor(bookmark.uri), json.toString()).apply()
} catch (_: Exception) {
// Prevent crash if SharedPreferences write fails
}
}

fun load(uri: String): Bookmark? {
val jsonStr = prefs.getString(keyFor(uri), null) ?: return null
return try {
val json = JSONObject(jsonStr)
Bookmark(
uri = uri,
title = json.optString("title", ""),
pageNumber = json.getInt("pageNumber"),
scrollY = json.getDouble("scrollY").toFloat(),
displayMode = json.getInt("displayMode"),
timestamp = json.getLong("timestamp"),
totalPages = json.optInt("totalPages", 1)
)
} catch (e: Exception) {
null
}
}

    fun remove(uri: String) {
        prefs.edit().remove(keyFor(uri)).apply()
    }

    fun getRecent(limit: Int = 20): List<Bookmark> {
        return try {
            prefs.all
                .filter { it.key.startsWith(KEY_PREFIX) }
                .mapNotNull { (_, value) ->
                    try {
                        val json = JSONObject(value as String)
                        val uri = json.optString("uri", "")
                        if (uri.isEmpty()) return@mapNotNull null
Bookmark(
uri = uri,
title = json.optString("title", ""),
pageNumber = json.optInt("pageNumber", 0),
scrollY = json.optDouble("scrollY", 0.0).toFloat(),
displayMode = json.optInt("displayMode", 0),
timestamp = json.optLong("timestamp", 0),
totalPages = json.optInt("totalPages", 1)
)
                    } catch (_: Exception) { null }
                }
                .sortedByDescending { it.timestamp }
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun keyFor(uri: String): String = KEY_PREFIX + Uri.encode(uri)

    companion object {
        private const val PREFS_NAME = "pdf_bookmarks"
        private const val KEY_PREFIX = "bm_"
    }
}
