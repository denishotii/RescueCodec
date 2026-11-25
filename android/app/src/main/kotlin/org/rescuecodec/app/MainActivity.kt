// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.service.RadioService
import org.rescuecodec.app.ui.AppViewModelFactory
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.ConnectionViewModel
import org.rescuecodec.app.ui.MessagesViewModel
import org.rescuecodec.app.ui.StatsViewModel
import org.rescuecodec.app.ui.TasksViewModel
import org.rescuecodec.app.ui.MinimalScreen

/** A minimal screen to drive the radio layer. The next job replaces it with the real UI. */
class MainActivity : ComponentActivity() {
    private val factory by lazy { AppViewModelFactory(application) }
    private val connection: ConnectionViewModel by viewModels { factory }
    private val tasks: TasksViewModel by viewModels { factory }
    private val messages: MessagesViewModel by viewModels { factory }
    private val stats: StatsViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.from(this)
        graph.start()
        // Reconnect to the last radio when the app opens, unless a Bluetooth permission is missing.
        val last = graph.lastRadio.get()
        if (savedInstanceState == null && last != null && !RadioService.running &&
            graph.link.state.value is LinkState.Disconnected &&
            (last is RadioTarget.Tcp || Permissions.bluetoothGranted(this))
        ) {
            RadioService.start(this)
        }
        setContent { MinimalScreen(connection, tasks, messages, stats) }
    }
}
