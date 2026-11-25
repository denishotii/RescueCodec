// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

/**
 * Code tables: canonical JSON names to wire numbers. They mirror `spec/rescuecodec/v2/rescuecodec.proto`
 * and `spec/vocab/sar.yaml`. Flag tables map a name to its bit number.
 */
object Codes {
    const val SPEC_VERSION = "2.0.0"
    const val MAX_ENCODE_BYTES = 200
    const val MAX_DECODE_BYTES = 233
    const val MAX_ID = 16383
    const val MAX_TRACK_POINTS = 16
    const val MAX_TEXT_BYTES = 180
    const val MAX_TEAM_BYTES = 12
    const val MAX_COUNT = 255
    const val MAX_RADIUS_M = 100_000
    const val MAX_ETA_MIN = 1440
    const val MAX_SECTOR = 65535

    /** Message body names in field order. */
    val BODY_TYPES = listOf("found", "track", "sector", "request", "hazard", "mayday", "ack", "text")
    val ID_REQUIRED = setOf("found", "request", "hazard", "mayday")

    val PRIORITY = mapOf("routine" to 1, "normal" to 2, "urgent" to 3, "critical" to 4)
    val DEFAULT_PRIORITY = mapOf(
        "mayday" to "critical", "found" to "urgent", "hazard" to "urgent", "ack" to "urgent",
        "request" to "normal", "text" to "normal", "sector" to "routine", "track" to "routine",
        "unknown" to "normal",
    )

    /** Suggested Meshtastic MeshPacket.priority per RescueCodec priority. */
    val MESHTASTIC_PRIORITY = mapOf("critical" to 110, "urgent" to 100, "normal" to 70, "routine" to 10)

    val ACCURACY = mapOf("gps" to 0, "gps_poor" to 1, "estimated" to 2, "area" to 3)
    val CONSCIOUSNESS = mapOf("conscious" to 1, "confused" to 2, "unconscious" to 3)
    val BREATHING = mapOf("normal" to 1, "difficult" to 2, "none" to 3)
    val MOBILITY = mapOf("walks" to 1, "cannot_walk" to 2, "trapped" to 3)
    val AGE = mapOf("child" to 1, "teen" to 2, "adult" to 3, "senior" to 4)

    /** Layout of `Found.state`: name, shift, bit width, table. */
    internal class StateField(val name: String, val shift: Int, val width: Int, val table: Map<String, Int>)

    internal val STATE_LAYOUT = listOf(
        StateField("consciousness", 0, 2, CONSCIOUSNESS),
        StateField("breathing", 2, 2, BREATHING),
        StateField("mobility", 4, 2, MOBILITY),
        StateField("age", 6, 3, AGE),
    )

    val INJURIES = mapOf(
        "hypothermia" to 0, "bleeding" to 1, "fracture" to 2, "head" to 3, "spine" to 4, "exhaustion" to 5,
        "other" to 6, "cardiac" to 7, "heat" to 8, "burn" to 9, "allergy" to 10,
    )
    val NEEDS = mapOf(
        "stretcher" to 0, "medic" to 1, "helicopter" to 2, "warmth" to 3, "water" to 4, "vehicle" to 5,
        "other" to 6, "aed" to 7, "oxygen" to 8, "first_aid" to 9, "rope" to 10, "light" to 11, "food" to 12,
        "radio" to 13,
    )
    val SECTOR_STATUS = mapOf("cleared" to 1, "partial" to 2, "blocked" to 3)
    val RESOURCE = mapOf(
        "stretcher" to 1, "medic" to 2, "helicopter" to 3, "vehicle" to 4, "searchers" to 5, "dog_team" to 6,
        "drone" to 7, "light" to 8, "water" to 9, "food" to 10, "warmth" to 11, "batteries" to 12,
        "rope_team" to 13, "ambulance" to 14, "fire_brigade" to 15, "other" to 16,
    )
    val URGENCY = mapOf("now" to 1, "soon" to 2, "later" to 3)
    val HAZARD = mapOf(
        "blocked_path" to 1, "rockfall" to 2, "water" to 3, "fire" to 4, "cliff" to 5, "ice" to 6, "animal" to 7,
        "power_line" to 8, "hunting" to 9, "weather" to 10, "other" to 11,
    )
    val SEVERITY = mapOf("low" to 1, "medium" to 2, "high" to 3)
    val MAYDAY_REASON = mapOf(
        "injured" to 1, "lost" to 2, "missing_member" to 3, "medical" to 4, "trapped" to 5, "threat" to 6,
        "other" to 7,
    )
    val ACK_STATUS = mapOf("accepted" to 1, "declined" to 2, "done" to 3)
}
