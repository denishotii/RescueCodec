// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.RescueCodec

/** Placeholder for the report engine (text/speech in, message out). */
object Engine {
    fun describe(): String = "RescueCodec engine on port ${RescueCodec.PORT_NUM}"
}
