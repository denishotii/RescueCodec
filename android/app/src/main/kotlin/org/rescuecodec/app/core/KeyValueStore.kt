// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import android.content.Context

/** Tiny key-value storage. Android uses SharedPreferences, tests use a map. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, Any?>()
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun getLong(key: String, default: Long) = (map[key] as? Long) ?: default
    override fun putLong(key: String, value: Long) { map[key] = value }
}

class PrefsKeyValueStore(context: Context, name: String = "rescuecodec") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
}
