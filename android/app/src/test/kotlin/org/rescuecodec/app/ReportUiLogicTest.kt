// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.rescuecodec.app.core.FieldKind
import org.rescuecodec.app.core.Fix
import org.rescuecodec.app.core.Labels
import org.rescuecodec.app.core.ReportFields
import org.rescuecodec.app.radio.ChannelJoin
import org.rescuecodec.app.ui.ReportViewModel
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.engine.Extractor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure logic behind the Report screen: chips, labels, quick phrases, channel links, string files. */
class ReportUiLogicTest {
    private val extractor = Extractor()

    @Test
    fun quickPhrasesTogetherBecomeOneFoundReport() {
        val en = extractor.extract("Child found, conscious, hypothermia, need stretcher", "en")
        assertFalse(en.unsupported)
        assertEquals("found", ReportFields.typeOf(en.message))
        val de = extractor.extract("Kind gefunden, ansprechbar, unterkühlt, Trage benötigt", "de")
        assertFalse(de.unsupported)
        assertEquals("found", ReportFields.typeOf(de.message))
        // Both languages give the same fields.
        assertEquals(ReportFields.body(en.message), ReportFields.body(de.message))
        assertEquals("hazard", ReportFields.typeOf(extractor.extract("Path blocked, rockfall", "en").message))
    }

    @Test
    fun chipEditsKeepTheMessageValid() {
        var m = extractor.extract("Child found, conscious", "en").message
        val breathing = ReportFields.def("found", "breathing")!!
        m = ReportFields.set(m, breathing, "none")
        assertEquals("none", ReportFields.body(m)["breathing"])
        val injuries = ReportFields.def("found", "injuries")!!
        m = ReportFields.toggle(m, injuries, "head")
        m = ReportFields.toggle(m, injuries, "bleeding")
        // Codes stay in table order: bleeding (1) before head (3).
        assertEquals(listOf("bleeding", "head"), ReportFields.body(m)["injuries"])
        m = ReportFields.toggle(m, injuries, "head")
        assertEquals(listOf("bleeding"), ReportFields.body(m)["injuries"])
        m = ReportFields.toggle(m, injuries, "bleeding")
        assertNull(ReportFields.body(m)["injuries"])
        m = ReportFields.remove(m, breathing)
        assertNull(ReportFields.body(m)["breathing"])
        // It still encodes (a found report needs an id).
        assertTrue(RescueCodec.encode(m + ("id" to 300)).isNotEmpty())
    }

    @Test
    fun switchingTypeGivesRequiredFieldsAndPriorityCanBeCleared() {
        var m: Map<String, Any?> = ReportFields.setType(ReportFields.textMessage("x"), "request")
        assertEquals("other", ReportFields.body(m)["resource"])
        val resource = ReportFields.def("request", "resource")!!
        assertEquals(m, ReportFields.remove(m, resource), "a required field cannot be removed")
        m = ReportFields.set(m, resource, "stretcher")
        m = ReportFields.setPriority(m, "critical")
        assertEquals("critical", ReportFields.priorityOf(m))
        assertEquals("priority", m.keys.first())
        m = ReportFields.setPriority(m, null)
        assertEquals("normal", ReportFields.priorityOf(m)) // default of a request
        assertNull(m["priority"])
        assertTrue(RescueCodec.encode(m + ("id" to 5)).isNotEmpty())
    }

    @Test
    fun everyEditableFieldHasLabelsInBothLanguages() {
        for (type in ReportFields.TYPES) for (d in ReportFields.defs(type)) {
            if (d.kind == FieldKind.NUMBER) continue
            for (code in d.options) {
                for (lang in listOf("en", "de")) {
                    assertTrue(Labels.has(lang, d.group, code), "no $lang word for ${d.group}.$code")
                }
            }
        }
        assertEquals("ansprechbar", Labels.value("de", "consciousness", "conscious"))
        assertEquals("Person gefunden", Labels.head("de", "found"))
        assertEquals("MAYDAY", Labels.head("en", "mayday"))
    }

    @Test
    fun channelLinksAreReadAndPublicKeysAreRefused() {
        val psk = ByteArray(32) { (it * 7 + 1).toByte() }.toByteString()
        val set = ChannelSet.Builder().settings(listOf(ChannelSettings.Builder().name("RescueCodec").psk(psk).build())).build()
        val link = assertNotNull(ChannelJoin.parse(ChannelJoin.toLink(set)))
        assertEquals("RescueCodec", link.primaryName)
        assertTrue(link.allPrivate)
        assertNotNull(ChannelJoin.parse(ChannelJoin.toLink(set) + "\n"))

        val defaultKey = byteArrayOf(1).toByteString()
        val pub = ChannelSet.Builder().settings(listOf(ChannelSettings.Builder().name("LongFast").psk(defaultKey).build())).build()
        assertFalse(assertNotNull(ChannelJoin.parse(ChannelJoin.toLink(pub))).allPrivate)

        assertNull(ChannelJoin.parse("https://example.com/#abc"))
        assertNull(ChannelJoin.parse("hello"))
        assertNull(ChannelJoin.parse("https://meshtastic.org/e/#!!!notbase64"))
    }

    @Test
    fun aLongTextWithThePositionIsShortenedSoItCanBeSent() {
        // The bug: a 180-byte free text plus the position was 201 to 204 bytes and could not be sent.
        val text = ReportFields.clampText("Wir sind am Waldweg hinter der Mühle, die Brücke über den Bach ist weggespült. ".repeat(3))
        val withPos = linkedMapOf<String, Any?>("pos" to Fix(52.52001, 13.40495, fresh = false).toPos()) + ReportFields.textMessage(text)
        assertTrue(runCatching { RescueCodec.encode(withPos + ("id" to 300)) }.isFailure, "too large without the fix")
        val out = ReportViewModel.fitForSending(withPos)
        assertNull(out["id"], "the id is added later, by Messenger.submit")
        assertEquals(withPos["pos"], out["pos"], "the position stays")
        val cut = ReportFields.body(out)["text"] as String
        assertTrue(cut.endsWith("…") && text.startsWith(cut.removeSuffix("…")), cut)
        // Fits with the largest id and with a small one.
        for (id in listOf(1, 127, 128, 16383)) assertTrue(RescueCodec.encode(out + ("id" to id)).size <= 200)
        // A short text, or a report that is not text, stays as it is.
        val short = linkedMapOf<String, Any?>("pos" to Fix(52.52001, 13.40495, fresh = false).toPos()) + ReportFields.textMessage("Weg frei")
        assertEquals(short, ReportViewModel.fitForSending(short))
        val found = extractor.extract("Child found, conscious", "en").message
        assertEquals(found, ReportViewModel.fitForSending(found))
    }

    @Test
    fun germanStringsMatchEnglishOnes() {
        val res = File("src/main/res")
        fun load(dir: String): Map<String, String> {
            val xml = File(res, "$dir/strings.xml").readText()
            return Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val en = load("values")
        val de = load("values-de")
        assertEquals(en.keys, de.keys, "missing in de: ${en.keys - de.keys}, missing in en: ${de.keys - en.keys}")
        val placeholder = Regex("""%\d?\$?[sdf]|%\d\$\.\d[f]""")
        for ((k, v) in en) {
            assertEquals(placeholder.findAll(v).count(), placeholder.findAll(de.getValue(k)).count(), "placeholders differ in $k")
            assertFalse(v.contains('—') || de.getValue(k).contains('—'), "em-dash in $k")
        }
    }
}
