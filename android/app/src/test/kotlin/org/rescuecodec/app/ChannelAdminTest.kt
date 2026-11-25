// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ChannelUrl
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.rescuecodec.app.radio.ChannelAdmin
import org.rescuecodec.app.radio.ChannelJoin
import org.rescuecodec.app.radio.CreateResult
import org.rescuecodec.app.radio.NameProblem
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A team makes, shares and removes channels from the phone alone. */
class ChannelAdminTest {
    private val eu = Config.LoRaConfig.Builder().use_preset(true).modem_preset(Config.LoRaConfig.ModemPreset.LONG_FAST)
        .region(Config.LoRaConfig.RegionCode.EU_868).build()

    /** Our radio: public LongFast primary, RescueCodec on slot 1, draeger-srs on 2, the rest switched off. */
    private val radio = listOf(
        teamChannel(0, name = "", psk = byteArrayOf(1)),
        teamChannel(1, name = "RescueCodec"),
        teamChannel(2, name = "draeger-srs", psk = ByteArray(16) { 7 }),
    ) + (3..7).map { teamChannel(it, name = "", psk = ByteArray(0), role = Channel.Role.DISABLED) }

    @Test
    fun aNewChannelGoesIntoAFreeSecondarySlotWithAFresh32ByteKey() = runBlocking {
        val written = ArrayList<Channel>()
        val r = ChannelAdmin.create(radio, eu, " Team 2 ", { written += it; AdminResult.Success(Unit) })
        val created = assertIs<CreateResult.Created>(r)
        assertEquals(3, created.index, "the first free slot after the ones in use")
        assertEquals("Team 2", created.name)
        val ch = written.single()
        assertEquals(3, ch.index)
        assertEquals(Channel.Role.SECONDARY, ch.role)
        assertEquals("Team 2", ch.settings?.name)
        assertEquals(32, ch.settings?.psk?.size, "AES-256")
        assertTrue(written.none { it.index == 0 }, "the primary is never written")

        // Two channels never get the same key.
        val again = ArrayList<Channel>()
        ChannelAdmin.create(radio, eu, "Team 3", { again += it; AdminResult.Success(Unit) })
        assertFalse(ch.settings!!.psk == again.single().settings!!.psk)
    }

    @Test
    fun theLinkOfANewChannelHasOnlyThatChannelAndJoinAddsItOnAnotherRadio() = runBlocking {
        val written = ArrayList<Channel>()
        val created = assertIs<CreateResult.Created>(ChannelAdmin.create(radio, eu, "Team 2", { written += it; AdminResult.Success(Unit) }))
        val link = assertNotNull(ChannelJoin.parse(created.link), "our own reader takes it")
        assertEquals(listOf("Team 2"), link.channels.map { it.name }, "only the new channel")
        assertContentEquals(written.single().settings!!.psk.toByteArray(), link.channels.single().psk.toByteArray())
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, link.set.lora_config?.region, "with the radio's LoRa settings")
        assertTrue(link.allPrivate)

        // A teammate's radio with only the default channel: joining adds Team 2 into slot 1 and keeps the primary.
        val other = listOf(teamChannel(0, name = "", psk = byteArrayOf(1))) + (1..7).map { teamChannel(it, name = "", psk = ByteArray(0), role = Channel.Role.DISABLED) }
        val plan = ChannelJoin.plan(other, eu, link)
        assertEquals(listOf(1 to "Team 2"), plan.add.map { it.first to it.second.name })
        assertNull(plan.lora, "same region: nothing else changes")
    }

    @Test
    fun theShareLinkUsesTheStandardEncodingTheOfficialAppReads() {
        val text = assertNotNull(ChannelAdmin.shareLink(radio, 2, eu))
        assertTrue(text.startsWith("https://meshtastic.org/e/#"))
        val frag = text.substringAfter('#')
        assertTrue(frag.none { it == '=' || it == '+' || it == '/' }, "URL-safe Base64 without padding: $frag")
        // The SDK's own parser (the same format as the official Meshtastic apps) reads it back.
        val set = assertNotNull(ChannelUrl.parse(text))
        assertEquals(listOf("draeger-srs"), set.settings.map { it.name })
        assertContentEquals(ByteArray(16) { 7 }, set.settings.single().psk.toByteArray())
        assertEquals(eu, set.lora_config)
        // And our reader takes it too.
        assertEquals("draeger-srs", ChannelJoin.parse(text)?.primaryName)
    }

    @Test
    fun aPublicOrUnknownChannelHasNoShareLink() {
        assertNull(ChannelAdmin.shareLink(radio, 0, eu), "the public default channel is not shared as a team channel")
        assertNull(ChannelAdmin.shareLink(radio, 5, eu), "a switched-off slot")
        assertNull(ChannelAdmin.shareLink(radio, 9, eu))
    }

    @Test
    fun namesAreCheckedBeforeAnythingIsWritten() = runBlocking {
        assertEquals(NameProblem.EMPTY, ChannelAdmin.checkName("   ", radio))
        assertEquals(NameProblem.TOO_LONG, ChannelAdmin.checkName("Bergwacht-12", radio), "12 bytes")
        assertNull(ChannelAdmin.checkName("Bergwacht12", radio), "11 bytes is fine")
        assertEquals(NameProblem.TOO_LONG, ChannelAdmin.checkName("Übungsplatz", radio), "Ü takes two bytes: 12")
        assertEquals(NameProblem.TAKEN, ChannelAdmin.checkName("rescuecodec", radio), "already on the radio, any case")
        assertEquals(NameProblem.TAKEN, ChannelAdmin.checkName("LongFast", radio), "the unnamed primary's name")
        val written = ArrayList<Channel>()
        val r = ChannelAdmin.create(radio, eu, "draeger-srs", { written += it; AdminResult.Success(Unit) })
        assertEquals(CreateResult.Reason.BAD_NAME, assertIs<CreateResult.Failed>(r).reason)
        assertTrue(written.isEmpty())
    }

    @Test
    fun theSuggestionSkipsNamesThatAreTaken() {
        assertEquals("Team 2", ChannelAdmin.suggestName(radio))
        assertEquals("Team 3", ChannelAdmin.suggestName(radio + teamChannel(3, name = "Team 2")))
        assertTrue(ChannelAdmin.nameBytes(ChannelAdmin.suggestName(radio)) <= ChannelAdmin.MAX_NAME_BYTES)
    }

    @Test
    fun aFullRadioSaysThereIsNoFreeSlot() = runBlocking {
        val full = (0..7).map { teamChannel(it, name = "ch$it", psk = ByteArray(16) { b -> (b + it).toByte() }) }
        assertNull(ChannelAdmin.freeSlot(full))
        val written = ArrayList<Channel>()
        val r = ChannelAdmin.create(full, eu, "Team 2", { written += it; AdminResult.Success(Unit) })
        assertEquals(CreateResult.Reason.NO_FREE_SLOT, assertIs<CreateResult.Failed>(r).reason)
        assertTrue(written.isEmpty())
    }

    @Test
    fun aRefusedWriteIsAFailure() = runBlocking {
        val r = ChannelAdmin.create(radio, eu, "Team 2", { AdminResult.Timeout })
        assertEquals(CreateResult.Reason.REFUSED, assertIs<CreateResult.Failed>(r).reason)
    }

    @Test
    fun removingSwitchesTheSlotOffAndNeverTouchesThePrimary() = runBlocking {
        val written = ArrayList<Channel>()
        val set: suspend (Channel) -> AdminResult<Unit> = { written += it; AdminResult.Success(Unit) }
        assertFalse(ChannelAdmin.removable(radio, 0))
        assertNotNull(ChannelAdmin.remove(radio, 0, set), "the primary cannot be removed")
        assertNotNull(ChannelAdmin.remove(radio, 5, set), "a switched-off slot has nothing to remove")
        assertTrue(written.isEmpty())

        assertTrue(ChannelAdmin.removable(radio, 2))
        assertNull(ChannelAdmin.remove(radio, 2, set))
        val off = written.single()
        assertEquals(2, off.index)
        assertEquals(Channel.Role.DISABLED, off.role)
        assertEquals(0, off.settings?.psk?.size ?: 0, "the key is gone from the radio")

        assertNotNull(ChannelAdmin.remove(radio, 1) { AdminResult.Timeout })
        Unit
    }

    @Test
    fun newKeysAreRandomAndNeverTheDefault() {
        val a = ChannelAdmin.newKey(SecureRandom())
        val b = ChannelAdmin.newKey(SecureRandom())
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }

    /** End to end with the SDK's fake radio: the admin message goes out, and the radio's channel list shows the new channel. */
    @Test
    fun onTheFakeRadioTheNewChannelAppearsInTheChannelList() = runBlocking {
        val me = 0x1234abcd
        val fake = fakeRadio("fake:admin", me, radio)
        val client = RadioClient.Builder().transport(fake).storage(InMemoryStorageProvider()).build()
        client.connect()
        eventually(5_000, "channels known") { client.channels.value.orEmpty().count { it.role != Channel.Role.DISABLED } == 3 }

        val job = async { ChannelAdmin.create(client.channels.value.orEmpty(), eu, "Team 2", { client.admin.setChannel(it) }) }
        // The radio answers each admin message with a routing ack, as real firmware does.
        val acked = HashSet<Int>()
        var sent: AdminMessage? = null
        withTimeout(10_000) {
            while (!job.isCompleted) {
                for (p in fake.outboundPackets().toList().filter { it.decoded?.portnum == PortNum.ADMIN_APP && it.id !in acked }) {
                    val m = AdminMessage.ADAPTER.decode(p.decoded!!.payload)
                    if (m.set_channel != null) sent = m
                    acked += p.id
                    fake.injectRoutingAck(p.id, me)
                }
                delay(20)
            }
        }
        assertEquals("Team 2", sent?.set_channel?.settings?.name)
        assertEquals(Channel.Role.SECONDARY, sent?.set_channel?.role)
        val created = assertIs<CreateResult.Created>(withTimeout(10_000) { job.await() })
        eventually(5_000, "the new channel in the client's list") {
            client.channels.value.orEmpty().any { it.index == created.index && it.settings?.name == "Team 2" && it.role == Channel.Role.SECONDARY }
        }
        assertEquals(Channel.Role.PRIMARY, client.channels.value.orEmpty().first { it.index == 0 }.role, "primary untouched")
        client.disconnect()
    }
}
