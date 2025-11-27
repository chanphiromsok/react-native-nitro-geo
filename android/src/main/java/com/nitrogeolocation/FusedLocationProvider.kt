package com.nitrogeolocation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.margelo.nitro.nitrogeolocation.AccuracyAndroid
import com.margelo.nitro.nitrogeolocation.GeoOptions
import com.margelo.nitro.nitrogeolocation.GeoWatchOptions

/**
 * Location provider using Google Play Services FusedLocationProviderClient
 * Preferred provider when Google Play Services is available
 */
class FusedLocationProvider(context: Context) : LocationProvider {
    
    companion object {
        private const val TAG = "FusedLocationProvider"
        private const val DEFAULT_INTERVAL = 10000L
        private const val DEFAULT_FASTEST_INTERVAL = 5000L
        private const val DEFAULT_DISTANCE_FILTER = 100f
    }
    
    private val fusedLocationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // For continuous updates
    private var watchCallback: LocationCallback? = null
    
    // For single location requests
    private var singleUpdateCallback: LocationCallback? = null
    private var timeoutRunnable: Runnable? = null
    
    @SuppressLint("MissingPermission")
    override fun getCurrentLocation(
        options: GeoOptions,
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        val maximumAge = options.maximumAge?.toLong() ?: Long.MAX_VALUE
        val forceRequestLocation = options.forceRequestLocation ?: false
        
        Log.d(TAG, "getCurrentLocation started (forceRequest: $forceRequestLocation, maximumAge: $maximumAge)")
        
        // If forceRequestLocation is true, skip cache and get fresh location immediately
        // This helps with WiFi-only scenarios where cached location can be very stale
        if (forceRequestLocation) {
            Log.d(TAG, "Force requesting fresh location (skipping cache)...")
            requestFreshLocation(options, startTime, onSuccess, onError)
            return
        }
        
        // Try to get last location first (only if not forcing)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                val lastLocationTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "lastLocation completed in ${lastLocationTime}ms, location: ${location != null}")
                
                if (location != null) {
                    val age = LocationUtils.getLocationAge(location)
                    Log.d(TAG, "Location age: ${age}ms, maximumAge: ${maximumAge}ms")
                    
                    if (age < maximumAge) {
                        Log.i(TAG, "Returning cached location (took ${lastLocationTime}ms)")
                        onSuccess(location)
                        return@addOnSuccessListener
                    }
                }
                
                // Request new location
                Log.d(TAG, "Cached location too old or unavailable, requesting fresh location...")
                requestFreshLocation(options, startTime, onSuccess, onError)
            }
            .addOnFailureListener { e ->
                val failTime = System.currentTimeMillis() - startTime
                Log.w(TAG, "lastLocation failed in ${failTime}ms: ${e.message}")
                // Try to request new location even if last location fails
                requestFreshLocation(options, startTime, onSuccess, onError)
            }
    }
    
    /**
     * Request a fresh location using getCurrentLocation API (Play Services 21.0.0+)
     * This is more reliable than requestLocationUpdates for single location requests,
     * especially in WiFi-only scenarios
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(
        options: GeoOptions,
        startTime: Long,
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val priority = getLocationPriority(options.accuracy, options.enableHighAccuracy)
        val timeout = options.timeout?.toLong() ?: 30000L
        
        Log.d(TAG, "requestFreshLocation - priority: $priority, timeout: $timeout")
        
        // Use CurrentLocationRequest for more reliable single location fetch
        // This API is better for WiFi-only scenarios as it actively tries to get location
        val currentLocationRequest = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setDurationMillis(timeout)
            .setMaxUpdateAgeMillis(0) // Always get fresh location
            .build()
        
        var hasResult = false
        
        // Setup timeout
        val timeoutHandler = Runnable {
            if (!hasResult) {
                hasResult = true
                val elapsed = System.currentTimeMillis() - startTime
                Log.e(TAG, "Fresh location request timed out after ${elapsed}ms")
                onError(Exception("Location request timed out"))
            }
        }
        timeoutRunnable = timeoutHandler
        mainHandler.postDelayed(timeoutHandler, timeout)
        
        fusedLocationClient.getCurrentLocation(currentLocationRequest, null)
            .addOnSuccessListener { location ->
                if (hasResult) return@addOnSuccessListener
                hasResult = true
                cancelTimeout()
                
                val elapsed = System.currentTimeMillis() - startTime
                if (location != null) {
                    Log.i(TAG, "Fresh location received in ${elapsed}ms via getCurrentLocation API")
                    onSuccess(location)
                } else {
                    Log.w(TAG, "getCurrentLocation returned null after ${elapsed}ms, falling back to requestLocationUpdates")
                    // Fallback to requestLocationUpdates if getCurrentLocation returns null
                    hasResult = false
                    requestSingleUpdate(options, startTime, onSuccess, onError)
                }
            }
            .addOnFailureListener { e ->
                if (hasResult) return@addOnFailureListener
                hasResult = true
                cancelTimeout()
                
                val elapsed = System.currentTimeMillis() - startTime
                Log.w(TAG, "getCurrentLocation API failed after ${elapsed}ms: ${e.message}, falling back")
                // Fallback to requestLocationUpdates
                hasResult = false
                requestSingleUpdate(options, startTime, onSuccess, onError)
            }
    }
    
    @SuppressLint("MissingPermission")
    private fun requestSingleUpdate(
        options: GeoOptions,
        startTime: Long,
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val priority = getLocationPriority(options.accuracy, options.enableHighAccuracy)
        val timeout = options.timeout?.toLong() ?: Long.MAX_VALUE
        
        Log.d(TAG, "requestSingleUpdate - priority: $priority, timeout: $timeout")
        
        val locationRequest = LocationRequest.Builder(priority, 1000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()
        
        var hasResult = false
        
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (hasResult) return
                hasResult = true
                val elapsed = System.currentTimeMillis() - startTime
                cancelTimeout()
                fusedLocationClient.removeLocationUpdates(this)
                singleUpdateCallback = null
                result.lastLocation?.let { 
                    Log.i(TAG, "Fresh location received in ${elapsed}ms")
                    onSuccess(it) 
                } ?: run {
                    Log.e(TAG, "Location result was null after ${elapsed}ms")
                    onError(Exception("Location result was null"))
                }
            }
            
            override fun onLocationAvailability(availability: LocationAvailability) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "onLocationAvailability: ${availability.isLocationAvailable} at ${elapsed}ms")
                if (!hasResult && !availability.isLocationAvailable) {
                    Log.w(TAG, "Location availability: false")
                }
            }
        }
        
        singleUpdateCallback = callback
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        
        // Setup timeout
        if (timeout != Long.MAX_VALUE) {
            timeoutRunnable = Runnable {
                if (!hasResult) {
                    hasResult = true
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.e(TAG, "Location request timed out after ${elapsed}ms")
                    fusedLocationClient.removeLocationUpdates(callback)
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
        val priority = getLocationPriority(options.accuracy, options.enableHighAccuracy)
        val interval = options.interval?.toLong() ?: DEFAULT_INTERVAL
        val fastestInterval = options.fastestInterval?.toLong() ?: DEFAULT_FASTEST_INTERVAL
        val distanceFilter = options.distanceFilter?.toFloat() ?: DEFAULT_DISTANCE_FILTER
        
        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .setMinUpdateDistanceMeters(distanceFilter)
            .build()
        
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocationChanged(it) }
            }
            
            override fun onLocationAvailability(availability: LocationAvailability) {
                // Log but don't error - location might become available
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location temporarily unavailable")
                }
            }
        }
        
        watchCallback = callback
        fusedLocationClient.requestLocationUpdates(locationRequest, watchCallback!!, Looper.getMainLooper())
    }
    
    override fun removeLocationUpdates() {
        Log.d(TAG, "removeLocationUpdates called")
        cancelTimeout()
        
        // Remove single update callback
        singleUpdateCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            singleUpdateCallback = null
        }
        
        // Remove watch callback
        watchCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            watchCallback = null
        }
    }
    
    private fun cancelTimeout() {
        timeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }
    
    private fun getLocationPriority(accuracy: AccuracyAndroid?, enableHighAccuracy: Boolean?): Int {
        if (accuracy != null) {
            return when (accuracy) {
                AccuracyAndroid.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
                AccuracyAndroid.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                AccuracyAndroid.LOW -> Priority.PRIORITY_LOW_POWER
                AccuracyAndroid.PASSIVE -> Priority.PRIORITY_PASSIVE
            }
        }
        return if (enableHighAccuracy == true) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
    }
}
