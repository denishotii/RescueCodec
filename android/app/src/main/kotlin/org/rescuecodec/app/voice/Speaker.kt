// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Read-aloud state: which card is being read, or why reading is not possible. */
sealed interface SpeakState {
    data object Idle : SpeakState
    data class Speaking(val id: String) : SpeakState

    /** No engine or no voice for the language on this phone. The screen shows the text instead. */
    data class Unavailable(val lang: String) : SpeakState
}

/**
 * Read-aloud with Android's own text-to-speech. We prefer an offline voice. If the phone has no
 * voice for the language, [state] becomes [SpeakState.Unavailable] and the screen falls back to text.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Triple<String, String, String>? = null

    private val _state = MutableStateFlow<SpeakState>(SpeakState.Idle)
    val state: StateFlow<SpeakState> = _state.asStateFlow()

    /** Reads [text] aloud in [lang] ("de" or "en"). [id] says which card asked, so its button can show "stop". */
    fun speak(id: String, text: String, lang: String) {
        if (failed) { _state.value = SpeakState.Unavailable(lang); return }
        if (!ready) {
            pending = Triple(id, text, lang)
            if (tts == null) init(lang)
            return
        }
        say(id, text, lang)
    }

    fun stop() {
        pending = null
        tts?.stop()
        if (_state.value is SpeakState.Speaking) _state.value = SpeakState.Idle
    }

    fun clearUnavailable() {
        if (_state.value is SpeakState.Unavailable) _state.value = SpeakState.Idle
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun init(lang: String) {
        tts = try {
            TextToSpeech(app) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    failed = true
                    _state.value = SpeakState.Unavailable(lang)
                    return@TextToSpeech
                }
                ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { idle(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { idle(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { idle(utteranceId) }
                })
                pending?.let { (id, text, l) -> pending = null; say(id, text, l) }
            }
        } catch (e: Exception) {
            failed = true
            _state.value = SpeakState.Unavailable(lang)
            null
        }
    }

    private fun idle(utteranceId: String?) {
        val s = _state.value
        if (s is SpeakState.Speaking && s.id == utteranceId) _state.value = SpeakState.Idle
    }

    private fun say(id: String, text: String, lang: String) {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(lang)
        val voice = runCatching {
            engine.voices.orEmpty()
                .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired && "notInstalled" !in it.features }
                .maxByOrNull { it.quality }
        }.getOrNull()
        val ok = if (voice != null) {
            engine.setVoice(voice) == TextToSpeech.SUCCESS
        } else {
            val r = engine.setLanguage(locale)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (!ok) {
            _state.value = SpeakState.Unavailable(lang)
            return
        }
        _state.value = SpeakState.Speaking(id)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            _state.value = SpeakState.Unavailable(lang)
        }
    }
}
