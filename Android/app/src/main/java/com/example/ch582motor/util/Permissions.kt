package com.example.ch582motor.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * До API 31 скан BLE считался определением местоположения: нужен
 * ACCESS_FINE_LOCATION и включённая служба геолокации.
 * С API 31 — свои BLUETOOTH_SCAN / BLUETOOTH_CONNECT, а флаг neverForLocation
 * в манифесте избавляет от геолокации совсем.
 */
object BlePermissions {

    val required: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Актуально только до API 31: без включённой геолокации скан ничего не найдёт. */
    fun locationServiceRequired(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun locationServiceEnabled(context: Context): Boolean {
        if (!locationServiceRequired()) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }
}
