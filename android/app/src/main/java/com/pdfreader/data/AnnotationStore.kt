package com.pdfreader.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores annotation metadata per document URI.
 * This is a lightweight index used by the annotations panel to show
 * highlight entries without needing to parse the PDF.
 */
class AnnotationStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class AnnotationEntry(
        val page: Int,
        val type: Int,
        val textSnippet: String,
        val quads: FloatArray,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addAnnotation(uri: String, entry: AnnotationEntry) {
        try {
            val list = loadAnnotations(uri).toMutableList()
            list.add(entry)
            saveList(uri, list)
        } catch (_: Exception) {}
    }

    fun loadAnnotations(uri: String): List<AnnotationEntry> {
        val jsonStr = prefs.getString(keyFor(uri), null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    AnnotationEntry(
                        page = obj.getInt("page"),
                        type = obj.getInt("type"),
                        textSnippet = obj.optString("text", ""),
                        quads = obj.optJSONArray("quads")?.let { arr ->
                            FloatArray(arr.length()) { i -> arr.optDouble(i, 0.0).toFloat() }
                        } ?: floatArrayOf(),
                        timestamp = obj.optLong("timestamp", 0)
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun removeAnnotation(uri: String, index: Int) {
        try {
            val list = loadAnnotations(uri).toMutableList()
            if (index in list.indices) {
                list.removeAt(index)
                saveList(uri, list)
            }
        } catch (_: Exception) {}
    }

    fun clearAnnotations(uri: String) {
        prefs.edit().remove(keyFor(uri)).apply()
    }

    private fun saveList(uri: String, list: List<AnnotationEntry>) {
        val arr = JSONArray()
        for (entry in list) {
            arr.put(JSONObject().apply {
                put("page", entry.page)
                put("type", entry.type)
                put("text", entry.textSnippet)
                put("quads", JSONArray().apply {
                    for (value in entry.quads) put(value)
                })
                put("timestamp", entry.timestamp)
            })
        }
        prefs.edit().putString(keyFor(uri), arr.toString()).commit()
    }

    private fun keyFor(uri: String): String = KEY_PREFIX + Uri.encode(uri)

    companion object {
        private const val PREFS_NAME = "pdf_annotations"
        private const val KEY_PREFIX = "annot_"
    }
}
