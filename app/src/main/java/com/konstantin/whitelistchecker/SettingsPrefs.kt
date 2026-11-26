package com.konstantin.whitelistchecker

import android.content.Context

/**
 * Настройки в SharedPreferences.
 * Интервал хранится в секундах — так его удобнее отдавать в интерфейс.
 */
object SettingsPrefs {

    private const val PREFS_NAME = "whitelist_checker_settings"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_INTERVAL_SEC = "interval_sec"
    private const val KEY_LAST_STATUS = "last_status"

    const val DEFAULT_INTERVAL_SEC = 60

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun getIntervalSeconds(context: Context): Int {
        val value = prefs(context).getInt(KEY_INTERVAL_SEC, DEFAULT_INTERVAL_SEC)
        return value.coerceAtLeast(0)
    }

    fun setIntervalSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_SEC, seconds.coerceAtLeast(0)).apply()
    }

    fun getLastStatus(context: Context): String? =
        prefs(context).getString(KEY_LAST_STATUS, null)

    fun setLastStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_LAST_STATUS, status).apply()
    }
}
