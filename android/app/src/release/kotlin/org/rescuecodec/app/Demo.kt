// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.content.Context
import android.content.Intent
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.sdk.AdminResult
import org.rescuecodec.app.radio.LinkState

/** Release builds have no demo data: every call does nothing. The debug build has the real one (src/debug). */
object Demo {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, graph: AppGraph, intent: Intent?) = Unit

    fun link(real: LinkState): LinkState = real

    fun radio(): Pair<List<Channel>, Config.LoRaConfig?>? = null

    fun writer(): (suspend (Channel) -> AdminResult<Unit>)? = null
}
