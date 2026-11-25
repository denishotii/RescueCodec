// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.rescuecodec.app.R

/** One permission we may ask for, with the plain-English reason. */
data class PermissionNeed(
    val permission: String,
    /** String resource with the explanation. */
    val reasonRes: Int,
    /** Without it the feature named in the reason does not work. */
    val required: Boolean,
)

/**
 * Which runtime permissions the app needs, by Android version.
 *
 * - Android 12 and newer (API 31+): Bluetooth needs "Nearby devices" (BLUETOOTH_SCAN and
 *   BLUETOOTH_CONNECT). No location needed to find radios.
 * - Android 8 to 11 (API 26 to 30): Bluetooth scanning needs location (ACCESS_FINE_LOCATION).
 * - Android 13 and newer (API 33+): the notification of the background service needs POST_NOTIFICATIONS.
 *   The service still runs without it, but the person does not see it.
 * - Location is also useful for the position in reports (optional).
 */
object Permissions {

    fun needs(sdk: Int, bluetooth: Boolean = true): List<PermissionNeed> {
        val list = ArrayList<PermissionNeed>()
        if (bluetooth) {
            if (sdk >= Build.VERSION_CODES.S) {
                list += PermissionNeed(Manifest.permission.BLUETOOTH_SCAN, R.string.perm_bt_scan, true)
                list += PermissionNeed(Manifest.permission.BLUETOOTH_CONNECT, R.string.perm_bt_connect, true)
            } else {
                list += PermissionNeed(Manifest.permission.ACCESS_FINE_LOCATION, R.string.perm_location_for_bt, true)
            }
        }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            list += PermissionNeed(Manifest.permission.POST_NOTIFICATIONS, R.string.perm_notifications, false)
        }
        if (list.none { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }) {
            list += PermissionNeed(Manifest.permission.ACCESS_FINE_LOCATION, R.string.perm_location_for_reports, false)
        }
        return list
    }

    /** Needs that are not granted yet. */
    fun missing(context: Context, bluetooth: Boolean = true): List<PermissionNeed> =
        needs(Build.VERSION.SDK_INT, bluetooth).filter { !granted(context, it.permission) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True if we may scan for and connect to Bluetooth radios. */
    fun bluetoothGranted(context: Context): Boolean =
        needs(Build.VERSION.SDK_INT, bluetooth = true).filter { it.required }.all { granted(context, it.permission) }

    /** Opens the system screen where the person can exempt the app from battery saving. */
    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
