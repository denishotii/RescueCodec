// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** WGS84 to UTM grid reference, the same formula as the dashboard (dashboard/src/lib/geo.ts). */
object Utm {
    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

    /** "32U 373404 5614293": zone, band, easting and northing in whole metres. */
    fun label(lat: Double, lon: Double): String {
        val e2 = F * (2 - F)
        val ep2 = e2 / (1 - e2)
        val zone = floor((lon + 180) / 6).toInt() + 1
        val lon0 = ((zone - 1) * 6 - 180 + 3) * (PI / 180)
        val phi = lat * PI / 180
        val lam = lon * PI / 180
        val n = A / sqrt(1 - e2 * sin(phi).pow(2))
        val t = tan(phi).pow(2)
        val c = ep2 * cos(phi).pow(2)
        val a = cos(phi) * (lam - lon0)
        val e4 = e2 * e2
        val e6 = e4 * e2
        val m = A * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
            (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
            (35 * e6 / 3072) * sin(6 * phi))
        val easting = K0 * n * (a + (1 - t + c) * a.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a.pow(5) / 120) + 500000
        var northing = K0 * (m + n * tan(phi) * (a.pow(2) / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a.pow(6) / 720))
        if (lat < 0) northing += 10000000
        val band = BANDS[floor((lat + 80) / 8).toInt().coerceIn(0, 19)]
        return "$zone$band ${easting.roundToLong()} ${northing.roundToLong()}"
    }
}
