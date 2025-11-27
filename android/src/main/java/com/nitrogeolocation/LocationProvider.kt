package com.nitrogeolocation

import android.location.Location
import com.margelo.nitro.nitrogeolocation.GeoOptions
import com.margelo.nitro.nitrogeolocation.GeoWatchOptions

/**
 * Interface for location providers
 * Implemented by FusedLocationProvider and LocationManagerProvider
 */
interface LocationProvider {
    
    /**
     * Get current location once
     */
    fun getCurrentLocation(
        options: GeoOptions,
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit
    )
    
    /**
     * Start continuous location updates
     */
    fun requestLocationUpdates(
        options: GeoWatchOptions,
        onLocationChanged: (Location) -> Unit,
        onError: (Exception) -> Unit
    )
    
    /**
     * Stop location updates
     */
    fun removeLocationUpdates()
}
