// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.engine

import org.rescuecodec.codec.RescueCodec

/** Entry point and version info of the engine library. */
object Engine {
    /** Version of this library. */
    const val VERSION: String = "0.1.0"

    /** One line for logs and the about screen. */
    fun describe(): String = "RescueCodec engine $VERSION, spec ${RescueCodec.SPEC_VERSION}, port ${RescueCodec.PORT_NUM}"
}
