// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The minimal screen starts, shows and survives a recreate (view models are wired to the app graph). */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun startsAndRecreates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
