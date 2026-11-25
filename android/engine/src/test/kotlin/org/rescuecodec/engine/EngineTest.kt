// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class EngineTest {
    @Test
    fun describesPort() {
        assertTrue(Engine.describe().contains("256"))
    }
}
