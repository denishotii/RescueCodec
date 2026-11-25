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
import org.rescuecodec.app.service.AppForeground
import org.rescuecodec.app.service.RadioService
import org.rescuecodec.app.ui.AppViewModelFactory
import org.rescuecodec.app.ui.NavRequest
import org.rescuecodec.app.ui.ReportViewModel
import org.rescuecodec.app.ui.RescueRoot
import org.rescuecodec.app.ui.Screen

/**
 * The one screen host. Everything on screen is Compose, see `ui/RescueRoot.kt`.
 *
 * `adb shell am start -n org.rescuecodec.app/.MainActivity --es screen TASKS` opens a screen
 * directly (TEAM, TASKS, STATS, RADIO, SETTINGS; the old names REPORT and FEED open Team, ACTIONS opens Tasks).
 * Debug builds also accept `--es connect host:port` to connect to a network radio (the simulator).
 */
class MainActivity : ComponentActivity() {
    /** A screen asked for by an intent while the app is already open (counter, screen). */
    private val navRequest = mutableStateOf<NavRequest?>(null)
    private var navCounter = 0

    private fun screenFrom(intent: Intent?): Screen? =
        Screen.from(intent?.getStringExtra("screen"))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenFrom(intent)?.let { navRequest.value = NavRequest(++navCounter, it, intent.getStringExtra("chat")) }
        injectReportText(intent)
    }

    /**
     * Debug builds only: type a sentence into the composer from the command line, with real umlauts
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
        // Bring the link back every time the activity is created, also after Android recreated it (process death,
        // rotation, language change): the person wants a link unless they pressed Disconnect. The service start
        // itself checks the Bluetooth permission and never throws.
        val last = graph.lastRadio.get()
        val idle = graph.link.state.value.let { it is LinkState.Disconnected || (it is LinkState.Error && it.retryAtMs == null) }
        if (last != null && graph.link.wantConnected() && !RadioService.running && idle) {
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
        val initial = screenFrom(intent) ?: Screen.TEAM
        // A notification names the chat to open ("chat" extra); otherwise the app opens the last chat used.
        val initialChat = intent?.getStringExtra("chat")?.takeIf { initial == Screen.TEAM }
        setContent { RescueRoot(graph, initial, navRequest.value, initialChat) }
        injectReportText(intent)
    }

    override fun onStart() {
        super.onStart()
        AppForeground.visible = true
        // The person sees the chat now: message and answer notifications are read.
        AppGraph.from(this).teamNotifier.clearRead()
    }

    override fun onStop() {
        AppForeground.visible = false
        super.onStop()
    }
}
