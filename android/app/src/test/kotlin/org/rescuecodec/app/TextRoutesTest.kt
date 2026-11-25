// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.rescuecodec.app.core.AppSettings
import org.rescuecodec.app.core.ChatId
import org.rescuecodec.app.core.MemoryKeyValueStore
import org.rescuecodec.app.core.TextRoutes
import org.rescuecodec.engine.TextMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who runs RescueCodec, the 24 h memory, the mode of each chat and the move from the old setting. */
class TextRoutesTest {
    private var now = 1_000_000_000L
    private val store = MemoryKeyValueStore()
    private val settings = AppSettings(store)
    private val routes = TextRoutes.inMemory(settings) { now }
    private val team = ChatId.Channel("RescueCodec")
    private val a = 0x0aL
    private val b = 0x0bL

    @Test
    fun autoFollowsWhoWasHeardAndForgetsAfter24Hours() = runBlocking<Unit> {
        assertEquals(TextMode.Wish(false, "nobody_known"), routes.wish(team, public = false))
        assertEquals(TextMode.Wish(false, "peer_unknown"), routes.wish(ChatId.Direct(a), public = false))
        routes.heardText(a, "RescueCodec")
        routes.heardRescueCodec(b, "RescueCodec")
        assertEquals(listOf(a), routes.meshtasticUsers("RescueCodec"))
        assertEquals(emptyList(), routes.meshtasticUsers("Bergwacht"), "per channel")
        assertEquals(TextMode.Wish(false, "meshtastic_users"), routes.wish(team, public = false))
        assertEquals(TextMode.Wish(true, "peer_rescuecodec"), routes.wish(ChatId.Direct(b), public = false))
        // A node that also sends RescueCodec packets is no Meshtastic user, on any channel.
        routes.heardRescueCodec(a, "Bergwacht")
        assertEquals(emptyList(), routes.meshtasticUsers("RescueCodec"))
        assertEquals(TextMode.Wish(true, "rescuecodec_only"), routes.wish(team, public = false))
        // 24 hours later both are forgotten.
        now += TextMode.MEMORY_MS + 1
        assertFalse(routes.isRescueCodec(b))
        routes.heardText(b, "RescueCodec")   // b now only writes normal texts
        assertEquals(listOf(b), routes.meshtasticUsers("RescueCodec"))
    }

    @Test
    fun eachChatKeepsItsModeAndPublicIsAlwaysNormal() = runBlocking<Unit> {
        assertEquals(TextMode.AUTO, routes.modeOf(team))
        routes.setMode(team, TextMode.NORMAL)
        routes.setMode(ChatId.Direct(a), TextMode.COMPRESSED)
        assertEquals(TextMode.NORMAL, routes.observeMode(team).first())
        assertEquals(TextMode.Wish(false, "mode_normal"), routes.wish(team, public = false))
        assertEquals(TextMode.Wish(true, "mode_compressed"), routes.wish(ChatId.Direct(a), public = false))
        assertEquals(TextMode.AUTO, routes.modeOf(ChatId.Channel("Other")))
        routes.setMode(ChatId.Channel("LongFast"), TextMode.COMPRESSED)
        assertEquals(TextMode.Wish(false, "public_channel"), routes.wish(ChatId.Channel("LongFast"), public = true))
        assertFailsWith<IllegalArgumentException> { routes.setMode(team, "zip") }
    }

    @Test
    fun theOldSwitchOffMakesChannelsAlwaysCompressedOnce() = runBlocking<Unit> {
        store.putLong(AppSettings.OLD_PLAIN_TEXT_KEY, 0)          // "Send plain text as normal Meshtastic text" was off
        settings.migrateTextMode()
        assertEquals(TextMode.COMPRESSED, settings.defaultChannelMode)
        assertEquals(TextMode.COMPRESSED, routes.modeOf(team))
        assertEquals(TextMode.AUTO, routes.modeOf(ChatId.Direct(a)), "direct chats start at auto")
        // The person picks Auto later: a second migration run does not undo it.
        settings.defaultChannelMode = TextMode.AUTO
        settings.migrateTextMode()
        assertEquals(TextMode.AUTO, settings.defaultChannelMode)
    }

    @Test
    fun theOldSwitchOnOrNeverSetMeansAuto() {
        val on = AppSettings(MemoryKeyValueStore().also { it.putLong(AppSettings.OLD_PLAIN_TEXT_KEY, 1) })
        assertEquals(TextMode.AUTO, on.defaultChannelMode)
        assertEquals(TextMode.AUTO, AppSettings(MemoryKeyValueStore()).defaultChannelMode)
        assertTrue(TextMode.MODES.contains(on.defaultChannelMode))
    }
}
