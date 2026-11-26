package com.konstantin.whitelistchecker

import android.content.Context
import org.json.JSONArray

/**
 * Списки сайтов теперь редактируются из интерфейса и хранятся в SharedPreferences
 * (JSON-массивы). Значения по умолчанию — те самые, что раньше были зашиты в код.
 */
object SitesStore {

    private const val PREFS_NAME = "whitelist_checker_sites"
    private const val KEY_WHITE = "white_sites"
    private const val KEY_BLOCK = "block_sites"

    const val TYPE_WHITE = "white"
    const val TYPE_BLOCK = "block"

    val DEFAULT_WHITE = listOf(
        "max.ru",
        "vk.ru",
        "vkvideo.ru",
        "yandex.ru",
        "ozon.ru",
        "sber.ru",
        "tbank.ru",
        "gosuslugi.ru",
        "vtb.ru",
        "dzen.ru"
    )

    val DEFAULT_BLOCK = listOf(
        "tiktok.com",
        "smoladmin.ru",
        "transphoto.org",
        "muttp.ru",
        "microsoft.com",
        "oppo.com",
        "wikipedia.org",
        "fandom.com",
        "weather.com",
        "twitch.tv"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun whiteSites(context: Context): List<String> = read(context, KEY_WHITE, DEFAULT_WHITE)

    fun blockSites(context: Context): List<String> = read(context, KEY_BLOCK, DEFAULT_BLOCK)

    fun add(context: Context, type: String, rawDomain: String): Boolean {
        val domain = normalize(rawDomain) ?: return false
        val key = keyFor(type) ?: return false
        val current = if (type == TYPE_WHITE) whiteSites(context) else blockSites(context)
        if (current.any { it.equals(domain, ignoreCase = true) }) return false
        write(context, key, current + domain)
        return true
    }

    fun remove(context: Context, type: String, rawDomain: String) {
        val domain = normalize(rawDomain) ?: return
        val key = keyFor(type) ?: return
        val current = if (type == TYPE_WHITE) whiteSites(context) else blockSites(context)
        write(context, key, current.filterNot { it.equals(domain, ignoreCase = true) })
    }

    private fun keyFor(type: String): String? = when (type) {
        TYPE_WHITE -> KEY_WHITE
        TYPE_BLOCK -> KEY_BLOCK
        else -> null
    }

    private fun read(context: Context, key: String, defaults: List<String>): List<String> {
        val raw = prefs(context).getString(key, null) ?: return defaults
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(defaults)
    }

    private fun write(context: Context, key: String, values: List<String>) {
        prefs(context).edit().putString(key, JSONArray(values).toString()).apply()
    }

    /** Приводим ввод к виду «домен», отбрасывая схему, путь и пробелы. */
    fun normalize(raw: String): String? {
        var value = raw.trim().lowercase()
        value = value.removePrefix("https://").removePrefix("http://")
        value = value.substringBefore('/')
        value = value.substringBefore('?').substringBefore('#')
        value = value.removeSuffix(".")
        if (value.isEmpty()) return null
        if (value.length > 253) return null
        if (value.any { it.isWhitespace() }) return null
        if (!value.contains('.')) return null
        if (value.startsWith('.') || value.endsWith('.')) return null
        val allowed = value.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        return if (allowed) value else null
    }
}
