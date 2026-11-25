// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.rescuecodec.app.voice.ModelStatus
import org.rescuecodec.app.voice.SpeechInput
import org.rescuecodec.app.voice.VoskModels
import org.rescuecodec.engine.Extractor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real Vosk models on recorded speech (made with macOS `say`, 16 kHz). Skipped when the
 * models are not on the device: run scripts/install-vosk-models.sh first. The emulator has no
 * microphone, so this feeds the audio from memory instead.
 */
@RunWith(AndroidJUnit4::class)
class VoiceModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    private fun pcm(asset: String): ShortArray {
        val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
        val data = bytes.copyOfRange(44, bytes.size) // plain PCM WAV: 44 byte header
        val out = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun recognise(lang: String, asset: String): String {
        val ext = VoskModels.externalDir(target, lang)
        assumeTrue("speech model $lang not installed (looked in $ext, exists=${ext?.exists()}, read=${ext?.canRead()}, files=${ext?.list()?.toList()})", VoskModels.status(target, lang) is ModelStatus.Ready)
        val speech = SpeechInput(target, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        return try { speech.transcribe(lang, pcm(asset)) } finally { speech.release() }
    }

    @Test
    fun englishSpeechBecomesAFoundReport() {
        val text = recognise("en", "speech_en.wav")
        println("VOSK en: $text")
        assertTrue(text.contains("child"), "heard: $text")
        val ex = Extractor().extract(text, "en")
        assertEquals("found", ex.message.keys.first { it in setOf("found", "text") }, "heard: $text")
    }

    @Test
    fun germanSpeechBecomesAFoundReport() {
        val text = recognise("de", "speech_de.wav")
        println("VOSK de: $text")
        assertTrue(text.contains("kind"), "heard: $text")
        val ex = Extractor().extract(text, "de")
        assertEquals("found", ex.message.keys.first { it in setOf("found", "text") }, "heard: $text")
    }
}
