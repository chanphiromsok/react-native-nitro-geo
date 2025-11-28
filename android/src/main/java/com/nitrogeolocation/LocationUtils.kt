package com.nitrogeolocation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.margelo.nitro.nitrogeolocation.GeoCoordinates
import com.margelo.nitro.nitrogeolocation.GeoPosition

/**
 * Utility functions for location operations
 */
object LocationUtils {
    
    /**
     * Check if Google Play Services is available
     */
    fun isGooglePlayServicesAvailable(context: Context): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }
    
    /**
     * Check if location permission is granted
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, 
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(
            context, 
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Get the age of a location in milliseconds
     */
    fun getLocationAge(location: Location): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        } else {
            System.currentTimeMillis() - location.time
        }
    }
    
    /**
     * Convert Android Location to GeoPosition
     */
    fun locationToGeoPosition(location: Location): GeoPosition {
        val altitudeAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters.toDouble()
        } else {
            null
        }

        // isMock() was added in API 31 (Android S), isFromMockProvider deprecated but still works
        val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        } else {
            null
        }

        val coords = GeoCoordinates(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            altitude = location.altitude,
            altitudeAccuracy = altitudeAccuracy,
            heading = location.bearing.toDouble(),
            speed = location.speed.toDouble()
        )

        return GeoPosition(
            coords = coords,
            timestamp = location.time.toDouble(),
            provider = location.provider,
            mocked = mocked
        )
    }
}
