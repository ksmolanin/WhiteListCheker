package com.konstantin.whitelistchecker.bypass

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки раздела «Обход»: профили подключений, режим VPN и списки
 * сайтов/приложений для режима «только выбранные».
 */
object BypassPrefs {

    private const val PREFS = "bypass_settings"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selected"
    private const val KEY_MODE = "mode"
    private const val KEY_SITES = "sites"
    private const val KEY_APPS = "apps"
    private const val KEY_NODE_LINK = "node_link"
    private const val KEY_NODE_SECRET = "node_secret"

    const val MODE_ALL = "all"
    const val MODE_SELECTED = "selected"

    data class Profile(val name: String, val link: String, val secret: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun profiles(context: Context): MutableList<Profile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                Profile(
                    name = o.optString("name", "Профиль"),
                    link = o.optString("link", ""),
                    secret = o.optString("secret", ""),
                )
            }
        }.getOrDefault(mutableListOf())
    }

    fun saveProfiles(context: Context, list: List<Profile>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("name", it.name).put("link", it.link).put("secret", it.secret))
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun addProfile(context: Context, profile: Profile): Int {
        val list = profiles(context)
        val existing = list.indexOfFirst { it.link == profile.link && it.secret == profile.secret }
        if (existing >= 0) return existing
        list.add(profile)
        saveProfiles(context, list)
        return list.size - 1
    }

    fun removeProfile(context: Context, index: Int) {
        val list = profiles(context)
        if (index in list.indices) {
            list.removeAt(index)
            saveProfiles(context, list)
            if (selectedIndex(context) >= list.size) {
                setSelectedIndex(context, 0)
            }
        }
    }

    fun selectedIndex(context: Context): Int =
        prefs(context).getInt(KEY_SELECTED, 0)

    fun setSelectedIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_SELECTED, index).apply()
    }

    fun selectedProfile(context: Context): Profile? =
        profiles(context).getOrNull(selectedIndex(context))

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_ALL) ?: MODE_ALL

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode).apply()
    }

    fun sites(context: Context): MutableList<String> =
        readArray(context, KEY_SITES).toMutableList()

    fun setSites(context: Context, sites: List<String>) =
        writeArray(context, KEY_SITES, sites)

    fun apps(context: Context): MutableList<String> =
        readArray(context, KEY_APPS).toMutableList()

    fun setApps(context: Context, apps: List<String>) =
        writeArray(context, KEY_APPS, apps)

    /** Настройки выходной ноды: ссылка на конференцию Телемост и ключ. */
    fun nodeLink(context: Context): String =
        prefs(context).getString(KEY_NODE_LINK, "") ?: ""

    fun setNodeLink(context: Context, link: String) =
        prefs(context).edit().putString(KEY_NODE_LINK, link).apply()

    fun nodeSecret(context: Context): String =
        prefs(context).getString(KEY_NODE_SECRET, "") ?: ""

    fun setNodeSecret(context: Context, secret: String) =
        prefs(context).edit().putString(KEY_NODE_SECRET, secret).apply()

    private fun readArray(context: Context, key: String): List<String> {
        val raw = prefs(context).getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i -> array.getString(i) }
        }.getOrDefault(emptyList())
    }

    private fun writeArray(context: Context, key: String, values: List<String>) {
        prefs(context).edit().putString(key, JSONArray(values).toString()).apply()
    }
}
