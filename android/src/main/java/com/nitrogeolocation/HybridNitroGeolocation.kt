package com.nitrogeolocation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitrogeolocation.*

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
                throw Exception("Location permission not granted")
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
