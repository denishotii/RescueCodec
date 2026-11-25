// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import org.rescuecodec.app.ui.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `--es screen X` launch extra and notification taps: old names still open the right place. */
class ScreenNamesTest {
    @Test
    fun oldScreenNamesStillOpenTheRightPlace() {
        assertEquals(Screen.CHATS, Screen.from("REPORT"))
        assertEquals(Screen.CHATS, Screen.from("feed"))
        assertEquals(Screen.CHATS, Screen.from("TEAM"))
        assertEquals(Screen.CHATS, Screen.from("CHATS"))
        assertEquals(Screen.TASKS, Screen.from("ACTIONS"))
        assertEquals(Screen.TASKS, Screen.from("TASKS"))
        assertEquals(Screen.RADIO, Screen.from("STATS"))
        assertEquals(Screen.RADIO, Screen.from("RADIO"))
        assertEquals(Screen.SETTINGS, Screen.from("SETTINGS"))
        assertNull(Screen.from("nonsense"))
        // Exactly three tabs.
        assertEquals(listOf(Screen.CHATS, Screen.TASKS, Screen.RADIO), Screen.entries.filter { it.isTab })
    }
}
