// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.runBlocking
import okio.ByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.sdk.AdminResult
import org.rescuecodec.app.radio.ChannelJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Joining a channel by QR code or link ADDS channels; it never replaces the primary or changes the region needlessly. */
class ChannelJoinTest {
    private fun settings(name: String, psk: ByteArray) = ChannelSettings.Builder().name(name).psk(ByteString.of(*psk)).build()
    private val drKey = ByteArray(16) { 7 }
    private val eu = Config.LoRaConfig.Builder().use_preset(true).modem_preset(Config.LoRaConfig.ModemPreset.LONG_FAST).region(Config.LoRaConfig.RegionCode.EU_868).build()
    private val unset = Config.LoRaConfig.Builder().use_preset(true).region(Config.LoRaConfig.RegionCode.UNSET).build()

    /** Our radio: public LongFast primary, RescueCodec on slot 1, the rest free. */
    private val radio = listOf(
        teamChannel(0, name = "", psk = byteArrayOf(1)),
        teamChannel(1, name = "RescueCodec"),
    ) + (2..7).map { teamChannel(it, name = "", psk = ByteArray(0), role = Channel.Role.DISABLED) }

    /** A link as the Meshtastic app shares it: LongFast primary plus the sponsor's private channel, with region. */
    private fun link(vararg s: ChannelSettings, lora: Config.LoRaConfig? = eu) =
        assertNotNull(ChannelJoin.parse(ChannelJoin.toLink(ChannelSet.Builder().settings(s.toList()).lora_config(lora).build())))

    @Test
    fun aLinkAddsItsPrivateChannelIntoAFreeSlotAndKeepsThePrimary() = runBlocking {
        val l = link(settings("", byteArrayOf(1)), settings("draeger-srs", drKey))
        val plan = ChannelJoin.plan(radio, eu, l)
        assertEquals(listOf(2 to "draeger-srs"), plan.add.map { it.first to it.second.name })
        assertEquals(listOf("LongFast"), plan.skippedPublic, "the public channel of the link is never added")
        assertTrue(plan.update.isEmpty() && plan.noRoom.isEmpty())
        assertNull(plan.lora, "region and preset stay: the radio has a region")

        val written = ArrayList<Channel>()
        val configs = ArrayList<Config>()
        val problem = ChannelJoin.apply(plan, { written += it; AdminResult.Success(Unit) }, { configs += it; AdminResult.Success(Unit) })
        assertNull(problem)
        assertEquals(1, written.size)
        assertEquals(2, written.single().index)
        assertEquals(Channel.Role.SECONDARY, written.single().role)
        assertTrue(written.none { it.index == 0 }, "the primary is never written")
        assertTrue(configs.isEmpty(), "no region change")
    }

    @Test
    fun aChannelAlreadyOnTheRadioIsNotAddedTwiceAndAStaleKeyIsUpdatedInPlace() {
        val have = ChannelJoin.plan(radio, eu, link(settings("RescueCodec", ByteArray(32) { (it + 1).toByte() })))
        assertEquals(listOf("RescueCodec"), have.already)
        assertTrue(!have.writes)
        val stale = ChannelJoin.plan(radio, eu, link(settings("RescueCodec", ByteArray(32) { 3 })))
        assertEquals(listOf(1), stale.update.map { it.first }, "same name, new key: same slot, no second RescueCodec")
        assertTrue(stale.add.isEmpty())
    }

    @Test
    fun theRegionIsOnlySetWhenTheRadioHasNone() {
        val l = link(settings("draeger-srs", drKey))
        assertNull(ChannelJoin.plan(radio, eu, l).lora)
        assertNull(ChannelJoin.plan(radio, null, l).lora, "unknown radio settings: leave them alone")
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, ChannelJoin.plan(radio, unset, l).lora?.region)
    }

    @Test
    fun aFullRadioSaysThereIsNoRoom() {
        val full = (0..7).map { teamChannel(it, name = "ch$it", psk = ByteArray(16) { b -> (b + it).toByte() }) }
        val plan = ChannelJoin.plan(full, eu, link(settings("draeger-srs", drKey)))
        assertEquals(listOf("draeger-srs"), plan.noRoom)
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun aRefusedChannelStopsWithAReason() = runBlocking {
        val plan = ChannelJoin.plan(radio, unset, link(settings("draeger-srs", drKey)))
        val configs = ArrayList<Config>()
        val problem = ChannelJoin.apply(plan, { AdminResult.Timeout }, { configs += it; AdminResult.Success(Unit) })
        assertNotNull(problem)
        assertTrue(configs.isEmpty(), "no region change after a failed channel")
    }
}
