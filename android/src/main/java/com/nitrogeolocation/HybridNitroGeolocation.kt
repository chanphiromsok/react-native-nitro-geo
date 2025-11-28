package com.nitrogeolocation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitrogeolocation.*

/**
 * Custom exception for permission denied errors
 */
class PermissionDeniedException(message: String) : Exception(message)

@DoNotStrip
@Keep
class HybridNitroGeolocation : HybridNitroGeolocationSpec() {
    
    companion object {
        private const val TAG = "NitroGeolocation"
    }

    private val context: Context
        get() = NitroModules.applicationContext
            ?: throw IllegalStateException("Application context not available")

    // Cached providers - reuse instead of creating new ones each time
    private var fusedProvider: FusedLocationProvider? = null
    private var locationManagerProvider: LocationManagerProvider? = null
    
    // Current active provider for watching
    private var watchProvider: LocationProvider? = null
    
    private var positionListeners: MutableList<(position: GeoPosition) -> Unit> = mutableListOf()
    private var errorListeners: MutableList<(error: GeoError) -> Unit> = mutableListOf()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Request location authorization.
     * On Android, this checks the current permission status.
     * Note: Actual permission requests must be done through React Native's PermissionsAndroid API
     * or expo-permissions, as we cannot show permission dialogs from a native module without an Activity.
     */
    override fun requestAuthorization(level: AuthorizationLevel): Promise<AuthorizationResult> {
        return Promise.async {
            // Check if location services are enabled
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
            if (!isLocationEnabled) {
                return@async AuthorizationResult.DISABLED
            }
            
            // Check permission status based on requested level
            val hasFineLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasBackgroundLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                // Before Android Q, foreground permission implies background access
                hasFineLocation || hasCoarseLocation
            }
            
            when (level) {
                AuthorizationLevel.ALWAYS -> {
                    if ((hasFineLocation || hasCoarseLocation) && hasBackgroundLocation) {
                        AuthorizationResult.GRANTED
                    } else {
                        AuthorizationResult.DENIED
                    }
                }
                AuthorizationLevel.WHENINUSE -> {
                    if (hasFineLocation || hasCoarseLocation) {
                        AuthorizationResult.GRANTED
                    } else {
                        AuthorizationResult.DENIED
                    }
                }
            }
        }
    }

    /**
     * Get or create the appropriate location provider based on options
     * Providers are cached and reused for better performance
     */
    private fun getLocationProvider(forceLocationManager: Boolean): LocationProvider {
        val playServicesAvailable = LocationUtils.isGooglePlayServicesAvailable(context)
        
        return if (forceLocationManager || !playServicesAvailable) {
            locationManagerProvider ?: LocationManagerProvider(context).also {
                Log.i(TAG, "Created LocationManagerProvider")
                locationManagerProvider = it
            }
        } else {
            fusedProvider ?: FusedLocationProvider(context).also {
                Log.i(TAG, "Created FusedLocationProvider")
                fusedProvider = it
            }
        }
    }

    override fun getCurrentPosition(options: GeoOptions): Promise<GeoPosition> {
//        println(fusedProvider)
        return Promise.async {
            if (!LocationUtils.hasLocationPermission(context)) {
                throw PermissionDeniedException("Location permission not granted. Call requestAuthorization() first or request permission via PermissionsAndroid.")
            }

            val forceLocationManager = options.forceLocationManager ?: false
            val provider = getLocationProvider(forceLocationManager)
            
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                provider.getCurrentLocation(
                    options,
                    onSuccess = { location ->
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.success(LocationUtils.locationToGeoPosition(location))
                            )
                        }
                    },
                    onError = { error ->
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }
                )
                
                // Handle cancellation
                continuation.invokeOnCancellation {
                    Log.d(TAG, "getCurrentPosition cancelled")
                    provider.removeLocationUpdates()
                }
            }
        }
    }

    override fun startObserving(options: GeoWatchOptions) {
        if (!LocationUtils.hasLocationPermission(context)) {
            notifyError(GeoError(PositionError.PERMISSION_DENIED, "Location permission not granted"))
            return
        }

        // Stop any existing observation first
        stopObserving()

        val forceLocationManager = options.forceLocationManager ?: false
        watchProvider = getLocationProvider(forceLocationManager)
        
        watchProvider?.requestLocationUpdates(
            options,
            onLocationChanged = { location ->
                notifyPosition(LocationUtils.locationToGeoPosition(location))
            },
            onError = { error ->
                notifyError(GeoError(PositionError.POSITION_UNAVAILABLE, error.message ?: "Unknown error"))
            }
        )
    }

    override fun stopObserving() {
        watchProvider?.removeLocationUpdates()
        watchProvider = null
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
        // Also stop observing when all listeners are removed
        stopObserving()
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
    
    /**
     * Clean up all resources - call when the module is being destroyed
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up NitroGeolocation")
        stopObserving()
        removeAllListeners()
        
        fusedProvider?.removeLocationUpdates()
        fusedProvider = null
        
        locationManagerProvider?.removeLocationUpdates()
        locationManagerProvider = null
    }
}
