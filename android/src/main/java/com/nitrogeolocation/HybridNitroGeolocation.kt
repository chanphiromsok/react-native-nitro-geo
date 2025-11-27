package com.nitrogeolocation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.ActivityCompat
import com.facebook.proguard.annotations.DoNotStrip
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitrogeolocation.*

@DoNotStrip
@Keep
class HybridNitroGeolocation : HybridNitroGeolocationSpec() {
    
    companion object {
        private const val TAG = "NitroGeolocation"
        private const val DEFAULT_DISTANCE_FILTER = 100f
        private const val DEFAULT_INTERVAL = 10000L
        private const val DEFAULT_FASTEST_INTERVAL = 5000L
    }

    private val context: Context
        get() = NitroModules.applicationContext
            ?: throw IllegalStateException("Application context not available")

    private val fusedLocationClient: FusedLocationProviderClient? by lazy {
        if (isGooglePlayServicesAvailable()) {
            LocationServices.getFusedLocationProviderClient(context)
        } else {
            null
        }
    }
    
    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }
    
    private var locationCallback: LocationCallback? = null
    private var locationListener: LocationListener? = null
    private var positionListeners: MutableList<(position: GeoPosition) -> Unit> = mutableListOf()
    private var errorListeners: MutableList<(error: GeoError) -> Unit> = mutableListOf()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isGooglePlayServicesAvailable(): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun getLocationAge(location: Location): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        } else {
            System.currentTimeMillis() - location.time
        }
    }

    private fun locationToGeoPosition(location: Location): GeoPosition {
        val altitudeAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters.toDouble()
        } else {
            null
        }

        val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
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

    @SuppressLint("MissingPermission")
    override fun getCurrentPosition(options: GeoOptions): Promise<GeoPosition> {
        return Promise.async {
            if (!hasLocationPermission()) {
                throw Exception("Location permission not granted")
            }

            val timeout = options.timeout?.toLong() ?: Long.MAX_VALUE
            val maximumAge = options.maximumAge?.toLong() ?: Long.MAX_VALUE
            val forceLocationManager = options.forceLocationManager ?: false

            // Use LocationManager if forced or if Play Services is not available
            if (forceLocationManager || fusedLocationClient == null) {
                getCurrentPositionWithLocationManager(options, timeout, maximumAge)
            } else {
                getCurrentPositionWithFused(options, timeout, maximumAge)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentPositionWithFused(options: GeoOptions, timeout: Long, maximumAge: Long): GeoPosition {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val client = fusedLocationClient!!
            
            // Try to get last location first
            client.lastLocation.addOnSuccessListener { location ->
                if (location != null && getLocationAge(location) < maximumAge) {
                    Log.i(TAG, "Returning cached location")
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(locationToGeoPosition(location)))
                    }
                    return@addOnSuccessListener
                }

                // Request new location
                val priority = getLocationPriority(options.accuracy, options.enableHighAccuracy)
                val locationRequest = LocationRequest.Builder(priority, 1000L)
                    .setMaxUpdates(1)
                    .setWaitForAccurateLocation(false)
                    .build()

                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val loc = result.lastLocation
                        if (loc != null && continuation.isActive) {
                            client.removeLocationUpdates(this)
                            continuation.resumeWith(Result.success(locationToGeoPosition(loc)))
                        }
                    }

                    override fun onLocationAvailability(availability: LocationAvailability) {
                        if (!availability.isLocationAvailable && continuation.isActive) {
                            client.removeLocationUpdates(this)
                            continuation.resumeWith(Result.failure(Exception("Location unavailable")))
                        }
                    }
                }

                client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())

                // Setup timeout
                if (timeout != Long.MAX_VALUE) {
                    mainHandler.postDelayed({
                        if (continuation.isActive) {
                            client.removeLocationUpdates(callback)
                            continuation.resumeWith(Result.failure(Exception("Location request timed out")))
                        }
                    }, timeout)
                }

                continuation.invokeOnCancellation {
                    client.removeLocationUpdates(callback)
                }
            }.addOnFailureListener { e ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(e))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentPositionWithLocationManager(options: GeoOptions, timeout: Long, maximumAge: Long): GeoPosition {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val manager = locationManager ?: run {
                continuation.resumeWith(Result.failure(Exception("LocationManager not available")))
                return@suspendCancellableCoroutine
            }

            val provider = getBestProvider(options.accuracy, options.enableHighAccuracy, manager) ?: run {
                continuation.resumeWith(Result.failure(Exception("No location provider available")))
                return@suspendCancellableCoroutine
            }

            // Try last known location first
            val lastLocation = manager.getLastKnownLocation(provider)
            if (lastLocation != null && getLocationAge(lastLocation) < maximumAge) {
                Log.i(TAG, "Returning cached location from LocationManager")
                continuation.resumeWith(Result.success(locationToGeoPosition(lastLocation)))
                return@suspendCancellableCoroutine
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        manager.removeUpdates(this)
                        continuation.resumeWith(Result.success(locationToGeoPosition(location)))
                    }
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    if (continuation.isActive) {
                        manager.removeUpdates(this)
                        continuation.resumeWith(Result.failure(Exception("Provider disabled")))
                    }
                }
            }

            mainHandler.post {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }

            // Setup timeout
            if (timeout != Long.MAX_VALUE) {
                mainHandler.postDelayed({
                    if (continuation.isActive) {
                        manager.removeUpdates(listener)
                        continuation.resumeWith(Result.failure(Exception("Location request timed out")))
                    }
                }, timeout)
            }

            continuation.invokeOnCancellation {
                manager.removeUpdates(listener)
            }
        }
    }

    private fun getBestProvider(accuracy: AccuracyAndroid?, enableHighAccuracy: Boolean?, manager: LocationManager): String? {
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
        return manager.getBestProvider(criteria, true)
    }

    @SuppressLint("MissingPermission")
    override fun startObserving(options: GeoWatchOptions) {
        if (!hasLocationPermission()) {
            notifyError(GeoError(PositionError.PERMISSION_DENIED, "Location permission not granted"))
            return
        }

        val forceLocationManager = options.forceLocationManager ?: false
        
        if (forceLocationManager || fusedLocationClient == null) {
            startObservingWithLocationManager(options)
        } else {
            startObservingWithFused(options)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startObservingWithFused(options: GeoWatchOptions) {
        val client = fusedLocationClient ?: return

        val priority = getLocationPriority(options.accuracy, options.enableHighAccuracy)
        val interval = options.interval?.toLong() ?: DEFAULT_INTERVAL
        val fastestInterval = options.fastestInterval?.toLong() ?: DEFAULT_FASTEST_INTERVAL
        val distanceFilter = options.distanceFilter?.toFloat() ?: DEFAULT_DISTANCE_FILTER

        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .setMinUpdateDistanceMeters(distanceFilter)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    notifyPosition(locationToGeoPosition(location))
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    notifyError(GeoError(PositionError.POSITION_UNAVAILABLE, "Location unavailable"))
                }
            }
        }

        client.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun startObservingWithLocationManager(options: GeoWatchOptions) {
        val manager = locationManager ?: return

        val provider = getBestProvider(options.accuracy, options.enableHighAccuracy, manager) ?: run {
            notifyError(GeoError(PositionError.POSITION_UNAVAILABLE, "No location provider available"))
            return
        }

        val interval = options.interval?.toLong() ?: DEFAULT_INTERVAL
        val distanceFilter = options.distanceFilter?.toFloat() ?: DEFAULT_DISTANCE_FILTER

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                notifyPosition(locationToGeoPosition(location))
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                notifyError(GeoError(PositionError.POSITION_UNAVAILABLE, "Provider disabled"))
            }
        }

        mainHandler.post {
            manager.requestLocationUpdates(provider, interval, distanceFilter, locationListener!!, Looper.getMainLooper())
        }
    }

    override fun stopObserving() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
            locationCallback = null
        }

        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
            locationListener = null
        }
    }

    override fun addPositionListener(callback: (position: GeoPosition) -> Unit) {
        positionListeners.add(callback)
    }

    override fun addErrorListener(callback: (error: GeoError) -> Unit) {
        errorListeners.add(callback)
    }

    override fun removeAllListeners() {
        positionListeners.clear()
        errorListeners.clear()
    }

    private fun notifyPosition(position: GeoPosition) {
        mainHandler.post {
            positionListeners.forEach { listener ->
                try {
                    listener(position)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying position listener", e)
                }
            }
        }
    }

    private fun notifyError(error: GeoError) {
        mainHandler.post {
            errorListeners.forEach { listener ->
                try {
                    listener(error)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying error listener", e)
                }
            }
        }
    }
}
