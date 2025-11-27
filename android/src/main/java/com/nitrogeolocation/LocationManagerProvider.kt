package com.nitrogeolocation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.margelo.nitro.nitrogeolocation.AccuracyAndroid
import com.margelo.nitro.nitrogeolocation.GeoOptions
import com.margelo.nitro.nitrogeolocation.GeoWatchOptions

/**
 * Location provider using Android's LocationManager
 * Fallback provider when Google Play Services is not available
 */
class LocationManagerProvider(context: Context) : LocationProvider {
    
    companion object {
        private const val TAG = "LocationManagerProvider"
        private const val DEFAULT_INTERVAL = 10000L
        private const val DEFAULT_DISTANCE_FILTER = 100f
    }
    
    private val locationManager: LocationManager = 
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // For continuous updates
    private var watchListener: LocationListener? = null
    
    // For single location requests
    private var singleUpdateListener: LocationListener? = null
    private var timeoutRunnable: Runnable? = null
    
    @SuppressLint("MissingPermission")
    override fun getCurrentLocation(
        options: GeoOptions,
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val maximumAge = options.maximumAge?.toLong() ?: Long.MAX_VALUE
        val timeout = options.timeout?.toLong() ?: Long.MAX_VALUE
        
        val provider = getBestProvider(options.accuracy, options.enableHighAccuracy)
        if (provider == null) {
            onError(Exception("No location provider available"))
            return
        }
        
        // Try last known location first
        val lastLocation = locationManager.getLastKnownLocation(provider)
        if (lastLocation != null && LocationUtils.getLocationAge(lastLocation) < maximumAge) {
            Log.i(TAG, "Returning cached location")
            onSuccess(lastLocation)
            return
        }
        
        // Request new location
        requestSingleUpdate(provider, timeout, onSuccess, onError)
    }
    
    @SuppressLint("MissingPermission")
    private fun requestSingleUpdate(
        provider: String,
        timeout: Long,
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit
    ) {
        var hasResult = false
        
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (hasResult) return
                hasResult = true
                cancelTimeout()
                locationManager.removeUpdates(this)
                singleUpdateListener = null
                onSuccess(location)
            }
            
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                if (hasResult) return
                hasResult = true
                cancelTimeout()
                locationManager.removeUpdates(this)
                singleUpdateListener = null
                onError(Exception("Provider disabled"))
            }
        }
        
        singleUpdateListener = listener
        
        mainHandler.post {
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
        
        // Setup timeout
        if (timeout != Long.MAX_VALUE) {
            timeoutRunnable = Runnable {
                if (!hasResult) {
                    hasResult = true
                    locationManager.removeUpdates(listener)
                    singleUpdateListener = null
                    onError(Exception("Location request timed out"))
                }
            }
            mainHandler.postDelayed(timeoutRunnable!!, timeout)
        }
    }
    
    @SuppressLint("MissingPermission")
    override fun requestLocationUpdates(
        options: GeoWatchOptions,
        onLocationChanged: (Location) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val provider = getBestProvider(options.accuracy, options.enableHighAccuracy)
        if (provider == null) {
            onError(Exception("No location provider available"))
            return
        }
        
        val interval = options.interval?.toLong() ?: DEFAULT_INTERVAL
        val distanceFilter = options.distanceFilter?.toFloat() ?: DEFAULT_DISTANCE_FILTER
        
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocationChanged(location)
            }
            
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                onError(Exception("Provider disabled"))
            }
        }
        
        watchListener = listener
        
        mainHandler.post {
            locationManager.requestLocationUpdates(
                provider, 
                interval, 
                distanceFilter, 
                watchListener!!, 
                Looper.getMainLooper()
            )
        }
    }
    
    override fun removeLocationUpdates() {
        Log.d(TAG, "removeLocationUpdates called")
        cancelTimeout()
        
        // Remove single update listener
        singleUpdateListener?.let {
            locationManager.removeUpdates(it)
            singleUpdateListener = null
        }
        
        // Remove watch listener
        watchListener?.let {
            locationManager.removeUpdates(it)
            watchListener = null
        }
    }
    
    private fun cancelTimeout() {
        timeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }
    
    private fun getBestProvider(accuracy: AccuracyAndroid?, enableHighAccuracy: Boolean?): String? {
        val criteria = Criteria().apply {
            when (accuracy) {
                AccuracyAndroid.HIGH -> {
                    this.accuracy = Criteria.ACCURACY_FINE
                    powerRequirement = Criteria.POWER_HIGH
                }
                AccuracyAndroid.BALANCED -> {
                    this.accuracy = Criteria.ACCURACY_COARSE
                    powerRequirement = Criteria.POWER_MEDIUM
                }
                AccuracyAndroid.LOW -> {
                    this.accuracy = Criteria.ACCURACY_COARSE
                    powerRequirement = Criteria.POWER_LOW
                }
                AccuracyAndroid.PASSIVE, null -> {
                    if (enableHighAccuracy == true) {
                        this.accuracy = Criteria.ACCURACY_FINE
                        powerRequirement = Criteria.POWER_HIGH
                    } else {
                        this.accuracy = Criteria.ACCURACY_COARSE
                        powerRequirement = Criteria.POWER_MEDIUM
                    }
                }
            }
        }
        
        var provider = locationManager.getBestProvider(criteria, true)
        
        // Fallback to any available provider
        if (provider == null) {
            val providers = locationManager.getProviders(true)
            provider = providers.firstOrNull()
        }
        
        return provider
    }
}
