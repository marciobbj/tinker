package com.pdfreader.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()

    var defaultDisplayMode: Int
        get() = prefs.getInt(KEY_DEFAULT_MODE, DISPLAY_MODE_VERTICAL)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_MODE, value).apply()

    companion object {
        private const val PREFS_NAME = "pdf_settings"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_DEFAULT_MODE = "default_display_mode"

        const val DISPLAY_MODE_VERTICAL = 0
        const val DISPLAY_MODE_BOOK = 1
    }
}
