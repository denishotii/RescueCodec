// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.app.service.RadioService
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.AppViewModelFactory
import org.rescuecodec.app.ui.ReportViewModel
import org.rescuecodec.app.ui.RescueRoot
import org.rescuecodec.app.ui.Screen

/**
 * The one screen host. Everything on screen is Compose, see `ui/RescueRoot.kt`.
 *
 * `adb shell am start -n org.rescuecodec.app/.MainActivity --es screen STATS` opens a screen
 * directly (REPORT, ACTIONS, FEED, STATS, RADIO, SETTINGS). The screenshot script uses it.
 * Debug builds also accept `--es connect host:port` to connect to a network radio (the simulator).
 */
class MainActivity : ComponentActivity() {
    /** A screen asked for by an intent while the app is already open (counter, screen). */
    private val navRequest = mutableStateOf<Pair<Int, Screen>?>(null)
    private var navCounter = 0

    private fun screenFrom(intent: Intent?): Screen? =
        intent?.getStringExtra("screen")?.let { n -> Screen.entries.firstOrNull { it.name.equals(n, true) } }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenFrom(intent)?.let { navRequest.value = ++navCounter to it }
        injectReportText(intent)
    }

    /**
     * Debug builds only: type a sentence into the Report screen from the command line, with real umlauts
     * (adb cannot type them). `--es report_text_b64 <base64 of the UTF-8 sentence>`.
     */
    private fun injectReportText(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val b64 = intent?.getStringExtra("report_text_b64") ?: return
        val text = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        ViewModelProvider(this, AppViewModelFactory(application))[ReportViewModel::class.java].onText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
        // Debug builds only: connect to a network radio from the command line (screenshots, tests).
        // adb shell am start -n org.rescuecodec.app/.MainActivity --es connect 10.0.2.2:4403
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("connect")?.let { addr ->
                val host = addr.substringBefore(':')
                val port = addr.substringAfter(':', "4403").toIntOrNull() ?: 4403
                RadioService.start(this, RadioTarget.Tcp(host, port))
            }
        }
        val initial = screenFrom(intent) ?: Screen.REPORT
        setContent { RescueRoot(graph, initial, navRequest.value) }
        injectReportText(intent)
    }
}
