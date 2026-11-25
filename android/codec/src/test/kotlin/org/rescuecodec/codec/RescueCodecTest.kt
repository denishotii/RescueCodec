// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

import kotlin.test.Test
import kotlin.test.assertEquals

class RescueCodecTest {
    @Test
    fun usesPrivateAppPort() {
        assertEquals(256, RescueCodec.PORT_NUM)
    }
}
