// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/** What the microphone side is doing. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Loading(val lang: String) : VoiceState
    data class Ready(val lang: String) : VoiceState
    data object Listening : VoiceState

    /** The model of [lang] is not on the phone. */
    data class NoModel(val lang: String, val hint: String) : VoiceState
    data object NoMicrophone : VoiceState
    data class Failed(val message: String) : VoiceState
}

/**
 * Offline speech to text with Vosk, for German and English. Hold to talk: [start] opens the
 * microphone, [stop] ends it and delivers the final text through [onFinal].
 *
 * We read the microphone ourselves (16 kHz mono) so the screen can show the loudness
 * ([level]) and so releasing the button ends the phrase at once. One model is kept in memory.
 */
class SpeechInput(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _text = MutableStateFlow("")

    /** Text recognised so far in this hold, including the part still being worked out. */
    val text: StateFlow<String> = _text.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Loudness 0..1, about 20 times a second while listening. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private var model: Model? = null
    private var modelLang: String? = null
    private var loading: Job? = null
    private var capture: Job? = null
    private val lock = Any()

    /** Loads the model of [lang] in the background so the first hold starts at once. */
    fun prepare(lang: String) {
        if (_state.value is VoiceState.Listening) return
        if (modelLang == lang && model != null) {
            _state.value = VoiceState.Ready(lang)
            return
        }
        loading?.cancel()
        loading = scope.launch(Dispatchers.IO) {
            when (val s = VoskModels.status(context, lang)) {
                is ModelStatus.Missing -> {
                    _state.value = VoiceState.NoModel(lang, s.hint.absolutePath)
                    return@launch
                }
                else -> Unit
            }
            _state.value = VoiceState.Loading(lang)
            try {
                val dir = VoskModels.ensure(context, lang)
                if (dir == null) {
                    _state.value = VoiceState.NoModel(lang, VoskModels.externalDir(context, lang)?.absolutePath.orEmpty())
                    return@launch
                }
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(dir.absolutePath)
                synchronized(lock) {
                    model?.close()
                    model = m
                    modelLang = lang
                }
                _state.value = VoiceState.Ready(lang)
            } catch (e: Throwable) {
                _state.value = VoiceState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Starts listening. Needs the RECORD_AUDIO permission and a loaded model. Returns false if it
     * could not start; the reason is in [state]. [onFinal] gets the full text after [stop].
     */
    @SuppressLint("MissingPermission")
    fun start(lang: String, onFinal: (String) -> Unit): Boolean {
        val m = synchronized(lock) { if (modelLang == lang) model else null }
        if (m == null) {
            prepare(lang)
            return false
        }
        if (capture?.isActive == true) return false
        _text.value = ""
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        } catch (e: Exception) {
            _state.value = VoiceState.NoMicrophone
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _state.value = VoiceState.NoMicrophone
            return false
        }
        val recognizer = Recognizer(m, sampleRate.toFloat())
        _state.value = VoiceState.Listening
        capture = scope.launch(Dispatchers.IO) {
            val done = StringBuilder()
            val buf = ShortArray(2048)
            try {
                record.startRecording()
                while (isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) { delay(5); continue }
                    _level.value = level(buf, n)
                    if (recognizer.acceptWaveForm(buf, n)) {
                        val t = JSONObject(recognizer.result).optString("text")
                        if (t.isNotBlank()) done.append(t).append(' ')
                        _text.value = done.toString().trim()
                    } else {
                        val p = JSONObject(recognizer.partialResult).optString("partial")
                        _text.value = (done.toString() + p).trim()
                    }
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) _state.value = VoiceState.Failed(e.message ?: "microphone error")
            } finally {
                runCatching { record.stop() }
                record.release()
                val last = runCatching { JSONObject(recognizer.finalResult).optString("text") }.getOrDefault("")
                if (last.isNotBlank()) done.append(last)
                recognizer.close()
                val full = done.toString().trim()
                _text.value = full
                _level.value = 0f
                if (_state.value is VoiceState.Listening) _state.value = VoiceState.Ready(lang)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { onFinal(full) }
            }
        }
        return true
    }

    /** Ends the hold. The final text arrives through the callback given to [start]. */
    fun stop() {
        capture?.cancel()
    }

    /**
     * Recognises 16 kHz mono 16-bit PCM from memory. Used by tests and for checking a model.
     * Blocking: call it off the main thread.
     */
    fun transcribe(lang: String, pcm: ShortArray): String {
        val dir = VoskModels.ensure(context, lang) ?: error("no speech model for $lang")
        val m = synchronized(lock) { if (modelLang == lang) model else null } ?: Model(dir.absolutePath)
        Recognizer(m, 16000f).use { r ->
            val out = StringBuilder()
            var i = 0
            while (i < pcm.size) {
                val n = minOf(4000, pcm.size - i)
                if (r.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) out.append(JSONObject(r.result).optString("text")).append(' ')
                i += n
            }
            out.append(JSONObject(r.finalResult).optString("text"))
            return out.toString().trim()
        }
    }

    fun release() {
        capture?.cancel()
        loading?.cancel()
        synchronized(lock) { model?.close(); model = null; modelLang = null }
    }

    private fun level(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
        val rms = sqrt(sum / n)
        // Speech sits around 0.02 to 0.2 RMS. Square root spreads the quiet part.
        return sqrt((rms * 6).coerceIn(0.0, 1.0)).toFloat()
    }
}
