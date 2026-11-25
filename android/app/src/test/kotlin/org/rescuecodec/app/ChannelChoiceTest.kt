// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import org.rescuecodec.app.core.ChannelChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChannelChoiceTest {
    private val longFast = teamChannel(0, name = "", psk = byteArrayOf(1))
    private val own = ByteArray(16) { 7 }

    @Test
    fun defaultKeysAreRecognised() {
        assertTrue(ChannelChoice.isDefaultKey(ByteArray(0)))
        assertTrue(ChannelChoice.isDefaultKey(byteArrayOf(1)))
        assertTrue(ChannelChoice.isDefaultKey(byteArrayOf(0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59, 0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01)))
        assertFalse(ChannelChoice.isDefaultKey(own))
        assertFalse(ChannelChoice.isDefaultKey(ByteArray(32) { 1 }))
    }

    @Test
    fun noChannelListMeansWait() {
        assertIs<ChannelChoice.Waiting>(ChannelChoice.resolve(null, -1))
        assertIs<ChannelChoice.Waiting>(ChannelChoice.resolve(emptyList(), 1))
    }

    @Test
    fun nameWinsOverEverythingElse() {
        val c = listOf(teamChannel(0, "Other", own), teamChannel(3, "RescueCodec", ByteArray(32) { 5 }))
        assertEquals(ChannelChoice.Ready(3, "RescueCodec", false), ChannelChoice.resolve(c, -1))
    }

    @Test
    fun namedChannelWithPublicKeyIsRefused() {
        val c = listOf(teamChannel(0, "RescueCodec", byteArrayOf(1)))
        assertIs<ChannelChoice.Problem>(ChannelChoice.resolve(c, -1))
    }

    @Test
    fun onlyPublicChannelIsAProblemNotChannelZero() {
        val p = ChannelChoice.resolve(listOf(longFast), -1)
        assertIs<ChannelChoice.Problem>(p)
        assertTrue(p.detail.startsWith("No private RescueCodec channel on this radio"))
    }

    @Test
    fun singleOwnKeyChannelIsPickedAndTwoAreAmbiguous() {
        assertEquals(ChannelChoice.Ready(1, "Team", true), ChannelChoice.resolve(listOf(longFast, teamChannel(1, "Team", own)), -1))
        assertIs<ChannelChoice.Problem>(ChannelChoice.resolve(listOf(teamChannel(0, "A", own), teamChannel(1, "B", own)), -1))
    }

    @Test
    fun disabledChannelsAreIgnored() {
        val off = teamChannel(2, "RescueCodec", own, org.meshtastic.proto.Channel.Role.DISABLED)
        assertIs<ChannelChoice.Problem>(ChannelChoice.resolve(listOf(longFast, off), -1))
    }

    @Test
    fun fixedIndexMustExistAndBePrivate() {
        val c = listOf(longFast, teamChannel(1, "Team", own))
        assertEquals(ChannelChoice.Ready(1, "Team", false), ChannelChoice.resolve(c, 1))
        assertIs<ChannelChoice.Problem>(ChannelChoice.resolve(c, 0))
        assertIs<ChannelChoice.Problem>(ChannelChoice.resolve(c, 5))
    }
}
