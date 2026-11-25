// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.app.Application

class RescueApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.start()
    }
}
