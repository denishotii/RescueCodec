// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/** A position for a report. [fresh] is true when the fix is less than two minutes old. */
data class Fix(val lat: Double, val lon: Double, val fresh: Boolean) {
    /** The `pos` object of a message (spec section 4). */
    fun toPos(): Map<String, Any?> = linkedMapOf<String, Any?>("lat" to round5(lat), "lon" to round5(lon)).also {
        if (!fresh) it["acc"] = "estimated"
    }

    private fun round5(v: Double) = Math.round(v * 100000.0) / 100000.0
}

/** Last known phone position. No updates are requested, so it costs no battery and works offline. */
object LocationHelper {
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context, now: Long = System.currentTimeMillis()): Fix? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best: Location? = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        return best?.let { Fix(it.latitude, it.longitude, fresh = now - it.time < 120_000) }
    }
}
