import { NitroModules } from 'react-native-nitro-modules'
import type {
  NitroGeolocation as NitroGeolocationSpec,
  GeoPosition,
  GeoCoordinates,
  GeoOptions,
  GeoWatchOptions,
  GeoError,
  AccuracyAndroid,
  SuccessCallback,
  ErrorCallback,
} from './specs/nitro-geolocation.nitro'
import { PositionError } from './specs/nitro-geolocation.nitro'

// Export all types
export type {
  NitroGeolocationSpec,
  GeoPosition,
  GeoCoordinates,
  GeoOptions,
  GeoWatchOptions,
  GeoError,
  AccuracyAndroid,
  SuccessCallback,
  ErrorCallback,
}

export { PositionError }

// Create the hybrid object
const NitroGeolocation =
  NitroModules.createHybridObject<NitroGeolocationSpec>('NitroGeolocation')

// Watch ID counter
let nextWatchId = 0
const watchCallbacks = new Map<
  number,
  { success: SuccessCallback; error?: ErrorCallback }
>()

/**
 * Geolocation API compatible with react-native-geolocation-service
 */
export const Geolocation = {
  /**
   * Get the current position
   * @param success - Success callback with position
   * @param error - Error callback
   * @param options - Position options
   */
  getCurrentPosition: (
    success: SuccessCallback,
    error?: ErrorCallback,
    options: GeoOptions = {}
  ): void => {
    NitroGeolocation.getCurrentPosition(options)
      .then(success)
      .catch((e: Error) => {
        if (error) {
          error({
            code: PositionError.INTERNAL_ERROR,
            message: e.message || 'Unknown error',
          })
        }
      })
  },

  /**
   * Watch position changes
   * @param success - Success callback with position
   * @param error - Error callback
   * @param options - Watch options
   * @returns Watch ID
   */
  watchPosition: (
    success: SuccessCallback,
    error?: ErrorCallback,
    options: GeoWatchOptions = {}
  ): number => {
    const watchId = nextWatchId++

    // Store callbacks
    watchCallbacks.set(watchId, { success, error })

    // Add listeners
    NitroGeolocation.addPositionListener(success)
    if (error) {
      NitroGeolocation.addErrorListener(error)
    }

    // Start observing if this is the first watcher
    if (watchCallbacks.size === 1) {
      NitroGeolocation.startObserving(options)
    }

    return watchId
  },

  /**
   * Clear a position watch
   * @param watchId - The watch ID returned by watchPosition
   */
  clearWatch: (watchId: number): void => {
    watchCallbacks.delete(watchId)

    // If no more watchers, stop observing
    if (watchCallbacks.size === 0) {
      NitroGeolocation.stopObserving()
      NitroGeolocation.removeAllListeners()
    }
  },

  /**
   * Stop all position observations
   */
  stopObserving: (): void => {
    watchCallbacks.clear()
    NitroGeolocation.stopObserving()
    NitroGeolocation.removeAllListeners()
  },
}

// Export the raw hybrid object for advanced usage
export { NitroGeolocation }

// Default export for convenience
export default Geolocation
