// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okio.ByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.toFrame
import org.meshtastic.sdk.testing.FakeRadioTransport

/** Polls [check] until it is true or [timeoutMs] passes. The SDK runs on real threads, so we wait in real time. */
suspend fun eventually(timeoutMs: Long = 10_000, what: String = "condition", check: suspend () -> Boolean) {
    try {
        withTimeout(timeoutMs) {
            while (!check()) delay(20)
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
    }
}

/** A mesh packet on port 256 as another radio would deliver it to the phone. */
fun privateAppPacket(from: Long, id: Int, payload: ByteArray, hopStart: Int = 3, hopLimit: Int = 2, channel: Int = 0): MeshPacket =
    MeshPacket.Builder().also { b ->
        b.from = from.toInt()
        b.channel = channel
        b.to = -1
        b.id = id
        b.hop_start = hopStart
        b.hop_limit = hopLimit
        b.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.PRIVATE_APP
            d.payload = ByteString.of(*payload)
        }.build()
    }.build()

/** A normal Meshtastic text (port 1), as the official app sends it. [to] -1 is a broadcast. */
fun textPacket(from: Long, id: Int, text: String, to: Long = -1, channel: Int = 0, hopStart: Int = 3, hopLimit: Int = 3): MeshPacket =
    MeshPacket.Builder().also { b ->
        b.from = from.toInt()
        b.channel = channel
        b.to = to.toInt()
        b.id = id
        b.hop_start = hopStart
        b.hop_limit = hopLimit
        b.rx_rssi = -97
        b.rx_snr = 6.25f
        b.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.TEXT_MESSAGE_APP
            d.payload = ByteString.of(*text.toByteArray(Charsets.UTF_8))
        }.build()
    }.build()

/** Packets the app handed to the radio on port 256. */
fun FakeRadioTransport.sentPrivateApp(): List<MeshPacket> = sentOn(PortNum.PRIVATE_APP)

/** Packets the app handed to the radio on port 1 (normal Meshtastic texts). */
fun FakeRadioTransport.sentText(): List<MeshPacket> = sentOn(PortNum.TEXT_MESSAGE_APP)

/** Packets the app handed to the radio on port 256 or port 1, in send order. */
fun FakeRadioTransport.sentMessages(): List<MeshPacket> = sentOn(PortNum.PRIVATE_APP, PortNum.TEXT_MESSAGE_APP)

private fun FakeRadioTransport.sentOn(vararg ports: PortNum): List<MeshPacket> {
    // The fake's list is not thread safe and the SDK writes to it from its own thread: try again on a clash.
    repeat(20) {
        try {
            return outboundPackets().filter { it.decoded?.portnum in ports }
        } catch (e: ConcurrentModificationException) {
            Thread.sleep(5)
        }
    }
    return outboundPackets().filter { it.decoded?.portnum in ports }
}

/**
 * A fake radio that answers the handshake and also reports its own node, as a real radio does.
 * (The SDK's own fake only sends `my_info`.)
 */
fun fakeRadio(identity: String, nodeNum: Int, channels: List<Channel> = listOf(teamChannel(0))): FakeRadioTransport {
    val me = FromRadio.Builder().also { b ->
        b.node_info = NodeInfo.Builder().also { n ->
            n.num = nodeNum
            n.user = User.Builder().also { u ->
                u.id = "!%08x".format(nodeNum)
                u.long_name = "Fake %08x".format(nodeNum)
                u.short_name = "FK"
            }.build()
        }.build()
    }.build()
    val frames = listOf(me.toFrame()) + channels.map { FromRadio.Builder().also { b -> b.channel = it }.build().toFrame() }
    return FakeRadioTransport(TransportIdentity(identity), frames = frames, autoHandshake = true, nodeNum = nodeNum)
}

/** A channel as the radio reports it. [psk] empty means no key, one byte means the public default key. */
fun teamChannel(
    index: Int,
    name: String = "RescueCodec",
    psk: ByteArray = ByteArray(32) { (it + 1).toByte() },
    role: Channel.Role = if (index == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY,
): Channel = Channel.Builder().also { c ->
    c.index = index
    c.role = role
    c.settings = ChannelSettings.Builder().also { s ->
        s.name = name
        s.psk = ByteString.of(*psk)
    }.build()
}.build()
