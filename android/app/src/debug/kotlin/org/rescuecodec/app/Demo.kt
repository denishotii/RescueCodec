// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.rescuecodec.app.core.MeshText
import org.rescuecodec.app.core.NodeName
import org.rescuecodec.app.data.MessageEntity
import org.rescuecodec.app.data.MessageState
import org.rescuecodec.app.data.StatNames
import org.rescuecodec.app.data.TaskEntity
import org.rescuecodec.app.data.add
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.radio.RadioTarget
import org.rescuecodec.codec.Json
import org.rescuecodec.codec.RescueCodec
import org.rescuecodec.engine.Extractor

/**
 * Debug builds only (this file is in src/debug; release has a stub that does nothing): demo content for screenshots,
 * the same as the approved mockup. No radio and no simulator needed.
 *
 * `adb shell am start -n org.rescuecodec.app/.MainActivity --ez demo true` wipes the local database and fills it with
 * channels, messages, a report, an answer and tasks. It also shows the radio as connected ("D-Node 2") on screen only;
 * nothing is sent. Restart the app once so the channel list of the radio is read. `--ez demo false` turns it off.
 */
object Demo {
    @Volatile private var active = false

    fun start(context: Context, graph: AppGraph, intent: Intent?) {
        val prefs = context.getSharedPreferences("demo", Context.MODE_PRIVATE)
        if (intent?.hasExtra("demo") == true) {
            val on = intent.getBooleanExtra("demo", false)
            prefs.edit().putBoolean("on", on).apply()
            if (on) load(context.applicationContext, graph)
        }
        active = prefs.getBoolean("on", false)
    }

    /** On screen only: the demo shows a connected radio. */
    fun link(real: LinkState): LinkState =
        if (active && (real is LinkState.Disconnected || real is LinkState.Error)) LinkState.Connected(RadioTarget.Ble("DE:MO:00:00:00:02", "D-Node 2"), OWN, "D-Node 2") else real

    private const val OWN = 0xD2L
    private const val D3 = 0xD3L
    private const val D7 = 0xD7L
    private const val BASE = 0xB0L
    private const val DRAEGER = 0xDAL
    private const val AK = 0xA1L
    private const val TEAM = "RescueCodec"

    private fun load(context: Context, graph: AppGraph) {
        val s = graph.settings
        s.lastTeamChannel = TEAM
        s.lastOwnNode = OWN
        s.lastRadioChannels = "0|0|$TEAM\n1|0|draeger-srs\n2|0|MeshMeister\n3|1|LongFast"
        s.lastChat = "dm:%08x".format(D7)
        graph.nodes.update(
            listOf(
                NodeName(OWN, "D-Node 2", "D2"), NodeName(D3, "D-Node 3", "D3"), NodeName(D7, "D-Node 7", "D7"),
                NodeName(BASE, "Base", "BS"), NodeName(DRAEGER, "Dräger KdoW", "DK"), NodeName(AK, "AK_Mobil_1", "AK"),
            ),
        )
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { fill(context, graph) }
                .onSuccess { android.util.Log.i("RescueDemo", "demo loaded") }
                .onFailure { android.util.Log.e("RescueDemo", "demo failed", it) }
        }
    }

    private suspend fun fill(context: Context, graph: AppGraph) {
        run {
            val db = graph.database
            db.messages().clear()
            db.tasks().clear()
            db.stats().clear()
            val now = System.currentTimeMillis()
            fun ago(min: Int) = now - min * 60_000L
            val ex = Extractor()
            // Positions near the phone, so the cards read "450 m NE" like the mockup (Frankfurt without a phone position).
            val here = org.rescuecodec.app.core.LocationHelper.lastKnown(context)
            val lat0 = here?.lat ?: 50.1060
            val lon0 = here?.lon ?: 8.6580
            val mPerLon = 111_320.0 * Math.cos(Math.toRadians(lat0))
            fun near(north: Double, east: Double) = mapOf("lat" to lat0 + north / 111_320.0, "lon" to lon0 + east / mPerLon)
            val pos = near(318.0, 318.0)
            fun report(text: String, id: Int, withPos: Boolean = true, at: Map<String, Double> = pos): Map<String, Any?> {
                val m = LinkedHashMap<String, Any?>()
                if (withPos) m["pos"] = at
                m.putAll(ex.extract(text, "en").message)
                m["id"] = id
                return m
            }
            fun text(t: String, id: Int) = MeshText.message(t) + ("id" to id)
            suspend fun row(
                msg: Map<String, Any?>, dir: String, node: Long, at: Long, state: String, channel: String? = TEAM,
                taskKey: String? = null, peer: Long? = null, public: Boolean = false, type: String? = null,
            ): Long = db.messages().insert(
                MessageEntity(
                    dir = dir, node = node, type = type ?: org.rescuecodec.app.core.ReportFields.typeOf(msg),
                    msgId = (msg["id"] as? Number)?.toInt() ?: 0, bytes = RescueCodec.encode(msg), json = Json.stringify(msg),
                    atMs = at, state = state, taskKey = taskKey, channelName = channel, channelIndex = 0, channelPublic = public, peer = peer,
                    rssi = if (dir == "in") -84 else null, snr = if (dir == "in") 2.5f else null, hops = if (dir == "in") 1 else null,
                ),
            )

            // Team channel, oldest first.
            val mayday = report("Mayday, team member injured, 2 persons", 12, at = near(-300.0, 0.0))
            row(mayday, "in", D3, ago(22), MessageState.RECEIVED, taskKey = "000000d3-12")
            val ownHazard = report("Danger: flooding at the creek", 41)
            row(ownHazard, "out", OWN, ago(40), MessageState.DELIVERED, taskKey = "000000d2-41")
            row(text("Path along the creek is flooded, use the forest road", 51), "in", D7, ago(15), MessageState.RECEIVED)
            row(text("Ok, we switch to the forest road", 42), "out", OWN, ago(14), MessageState.DELIVERED)
            val ack = mapOf("ack" to linkedMapOf<String, Any?>("ref" to 41, "node" to "!000000d2", "status" to "accepted", "eta" to 10, "team" to "Base"))
            row(ack, "in", BASE, ago(6), MessageState.RECEIVED, taskKey = "000000d2-41", type = "ack")
            val found = report("Child found, conscious, hypothermia, fracture, need stretcher, 2 helpers", 21)
            row(found, "in", D7, ago(4), MessageState.RECEIVED, taskKey = "000000d7-21")

            // Other channels and a direct message.
            val mm = row(text("anyone near hall 8?", 3), "in", AK, ago(25), MessageState.RECEIVED, channel = "MeshMeister")
            graph.chatReads.markRead("ch:MeshMeister", mm)
            row(text("Treffpunkt Halle 8, 13:30", 7), "in", DRAEGER, ago(12), MessageState.RECEIVED, channel = "draeger-srs")
            row(text("radio check", 4), "in", AK, ago(50), MessageState.RECEIVED, channel = "LongFast", public = true)
            row(text("ok, coming", 43), "out", OWN, ago(30), MessageState.DELIVERED, peer = D7)

            // Tasks.
            fun task(key: String, node: Long, id: Int, msg: Map<String, Any?>, state: String, updated: Long, local: Boolean = false, own: String? = null, answers: String = "[]", rank: Int = 1) =
                TaskEntity(
                    key = key, node = node, msgId = id, type = org.rescuecodec.app.core.ReportFields.typeOf(msg), json = Json.stringify(msg), local = local,
                    state = state, meshDelivered = local, createdMs = updated, updatedMs = updated, answersJson = answers, ownStatus = own, rank = rank,
                    channelIndex = 0, channelName = TEAM,
                )
            val t = db.tasks()
            t.upsert(task("000000d3-12", D3, 12, mayday, "open", ago(2), rank = 0))
            t.upsert(task("000000d7-21", D7, 21, found, "open", ago(4)))
            t.upsert(task("000000b0-8", BASE, 8, report("Need medic at hall 8 entrance", 8), "open", ago(9)))
            t.upsert(
                task(
                    "000000d3-15", D3, 15, report("Path blocked, fallen tree", 15, withPos = false), "accepted", ago(20), own = "accepted",
                    answers = """[{"from":${OWN},"status":"accepted","at":${ago(20)}}]""",
                ),
            )
            t.upsert(
                task(
                    "000000d2-41", OWN, 41, ownHazard, "accepted", ago(6), local = true,
                    answers = """[{"from":${BASE},"status":"accepted","team":"Base","eta":10,"at":${ago(6)}}]""",
                ),
            )

            // Airtime and savings.
            val st = db.stats()
            st.add(StatNames.TX_MESSAGES, 14)
            st.add(StatNames.TX_BYTES, 312)
            st.add(StatNames.TX_DELIVERED, 12)
            st.add(StatNames.RX_MESSAGES, 23)
            st.add(StatNames.RX_BYTES, 540)
            st.add(StatNames.REPORT_COUNT, 6)
            st.add(StatNames.REPORT_TEXT_BYTES, 1252)
            st.add(StatNames.REPORT_CODED_BYTES, 180)
            st.add(StatNames.REPORT_AIRTIME_SAVED_MS, 9_400)
        }
    }
}
