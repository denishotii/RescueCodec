// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings the screens watch: language and day/night. Backed by [AppSettings] and the key-value
 * store, so they survive a restart. English is the default; the toggle switches the whole app and
 * the readable text of decoded messages.
 */
class UiPrefs(private val settings: AppSettings, private val store: KeyValueStore) {
    private val _language = MutableStateFlow(settings.language.takeIf { it in LANGUAGES } ?: "en")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _theme = MutableStateFlow(store.getString(THEME_KEY) ?: "auto")

    /** "auto", "day" or "night". */
    val theme: StateFlow<String> = _theme.asStateFlow()

    fun setLanguage(lang: String) {
        val l = if (lang in LANGUAGES) lang else "en"
        settings.language = l
        _language.value = l
    }

    fun toggleLanguage() = setLanguage(if (_language.value == "en") "de" else "en")

    private val _sounds = MutableStateFlow(store.getString(SOUNDS_KEY) != "off")

    /** Short in-app sound and haptic tick for a new message or task while the app is open. On by default. */
    val sounds: StateFlow<Boolean> = _sounds.asStateFlow()

    fun setSounds(on: Boolean) {
        store.putString(SOUNDS_KEY, if (on) "on" else "off")
        _sounds.value = on
    }

    private val _showPublic = MutableStateFlow(settings.showPublicChannel)

    /** The public channel (LongFast) shows in the chat list and its messages are received. Off by default. */
    val showPublic: StateFlow<Boolean> = _showPublic.asStateFlow()

    fun setShowPublic(on: Boolean) {
        settings.showPublicChannel = on
        _showPublic.value = on
    }

    private val _compactText = MutableStateFlow(settings.compactText)

    /** Compact texts (word tokens) on chats that pack. Off by default. */
    val compactText: StateFlow<Boolean> = _compactText.asStateFlow()

    fun setCompactText(on: Boolean) {
        settings.compactText = on
        _compactText.value = on
    }

    private val _teamVocabulary = MutableStateFlow(settings.teamVocabulary)

    /** Team vocabulary of compact texts: auto (default), general or one domain (spec section 3.8.6). */
    val teamVocabulary: StateFlow<String> = _teamVocabulary.asStateFlow()

    fun setTeamVocabulary(setting: String) {
        settings.teamVocabulary = setting
        _teamVocabulary.value = settings.teamVocabulary
    }

    fun setTheme(mode: String) {
        store.putString(THEME_KEY, mode)
        _theme.value = mode
    }

    companion object {
        val LANGUAGES = listOf("en", "de")
        private const val THEME_KEY = "theme"
        private const val SOUNDS_KEY = "sounds"
    }
}
