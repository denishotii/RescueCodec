// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.Manifest
import org.rescuecodec.app.intent.IntentApi
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.codec.RescueCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionsAndIntentTest {
    private fun names(sdk: Int, bluetooth: Boolean = true) = Permissions.needs(sdk, bluetooth).map { it.permission }
    private fun required(sdk: Int) = Permissions.needs(sdk).filter { it.required }.map { it.permission }

    @Test
    fun android12AndNewerNeedsNearbyDevicesNotLocation() {
        for (sdk in listOf(31, 32, 33, 34, 35)) {
            assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), required(sdk), "sdk $sdk")
        }
    }

    @Test
    fun android8To11NeedsLocationToScan() {
        for (sdk in 26..30) {
            assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), required(sdk), "sdk $sdk")
            assertTrue(Manifest.permission.POST_NOTIFICATIONS !in names(sdk))
        }
    }

    @Test
    fun notificationsAreAskedFromAndroid13OnAndAreOptional() {
        assertTrue(Manifest.permission.POST_NOTIFICATIONS !in names(32))
        for (sdk in 33..35) {
            val n = Permissions.needs(sdk).single { it.permission == Manifest.permission.POST_NOTIFICATIONS }
            assertEquals(false, n.required)
        }
    }

    @Test
    fun aNetworkRadioNeedsNoBluetoothPermission() {
        assertTrue(Permissions.needs(35, bluetooth = false).none { it.required })
        assertEquals(setOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION), names(35, bluetooth = false).toSet())
    }

    @Test
    fun everyNeedHasAnExplanation() {
        for (sdk in 26..35) assertTrue(Permissions.needs(sdk).all { it.reasonRes != 0 })
    }

    @Test
    fun jsonInputIsUsedAsGivenAndGetsAPosition() {
        val (msg, err) = IntentApi.buildMessage(
            IntentApi.Request(json = """{"id":7,"found":{"age":"child"}}""", lat = 50.69, lon = 7.21, priority = "critical"),
        )
        assertNull(err)
        assertEquals(7, (msg!!["id"] as Number).toInt())
        assertEquals(mapOf("lat" to 50.69, "lon" to 7.21), msg["pos"])
        assertEquals("critical", msg["priority"])
        assertNotNull(RescueCodec.encode(msg))
    }

    @Test
    fun anExistingPositionIsNotOverwritten() {
        val (msg, _) = IntentApi.buildMessage(IntentApi.Request(json = """{"id":7,"pos":{"lat":1.0,"lon":2.0},"found":{}}""", lat = 50.0, lon = 7.0))
        assertEquals(1.0, ((msg!!["pos"]) as Map<*, *>)["lat"])
    }

    @Test
    fun germanAndEnglishTextBecomeFoundReports() {
        for (text in listOf("Kind gefunden, ansprechbar, unterkühlt, wir brauchen eine Trage", "Found a child, conscious, hypothermia, need a stretcher")) {
            val (msg, err) = IntentApi.buildMessage(IntentApi.Request(text = text))
            assertNull(err)
            assertNotNull(msg!!["found"], "found body for: $text")
        }
    }

    @Test
    fun textThatCannotBeCodedFallsBackToText() {
        val (msg, err) = IntentApi.buildMessage(IntentApi.Request(text = "Treffpunkt ist jetzt am Gasthaus statt am Parkplatz"))
        assertNull(err)
        assertNotNull(msg!!["text"])
    }

    @Test
    fun badInputGivesStableErrorCodes() {
        assertEquals("invalid_json", IntentApi.buildMessage(IntentApi.Request(json = "{oops")).second!!.first)
        assertEquals("invalid_json", IntentApi.buildMessage(IntentApi.Request(json = "[1,2]")).second!!.first)
        assertEquals("no_input", IntentApi.buildMessage(IntentApi.Request()).second!!.first)
        assertEquals("no_input", IntentApi.buildMessage(IntentApi.Request(json = "  ", text = "")).second!!.first)
    }
}
