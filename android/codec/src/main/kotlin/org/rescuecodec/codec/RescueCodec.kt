// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

/** Entry point of the pure Kotlin codec library. Real wire code lands here later. */
object RescueCodec {
    /** Meshtastic port used for RescueCodec packets (PRIVATE_APP). */
    const val PORT_NUM: Int = 256

    const val LIBRARY_VERSION: String = "0.1.0"
}
