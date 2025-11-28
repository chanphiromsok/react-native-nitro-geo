import { type HybridObject } from 'react-native-nitro-modules'

/**
 * Accuracy options for Android location requests
 */
export type AccuracyAndroid = 'high' | 'balanced' | 'low' | 'passive'

/**
 * Authorization level for location permission requests (iOS only)
 */
export type AuthorizationLevel = 'always' | 'whenInUse'

/**
 * Result of location authorization request
 */
export type AuthorizationResult = 'disabled' | 'granted' | 'denied' | 'restricted'

/**
 * Error codes for geolocation operations
 */
export enum PositionError {
  PERMISSION_DENIED = 1,
  POSITION_UNAVAILABLE = 2,
  TIMEOUT = 3,
  PLAY_SERVICE_NOT_AVAILABLE = 4,
  SETTINGS_NOT_SATISFIED = 5,
  INTERNAL_ERROR = -1,
}

/**
 * Geographic coordinates
 */
export interface GeoCoordinates {
  latitude: number
  longitude: number
  accuracy: number
  altitude: number | undefined
  altitudeAccuracy: number | undefined
  heading: number | undefined
  speed: number | undefined
}

/**
 * Position returned from geolocation requests
 */
export interface GeoPosition {
  coords: GeoCoordinates
  timestamp: number
  provider: string | undefined
  mocked: boolean | undefined
}

/**
 * Error returned from geolocation requests
 */
export interface GeoError {
  code: PositionError
  message: string
}

/**
 * Options for getCurrentPosition
 */
export interface GeoOptions {
  timeout?: number
  maximumAge?: number
  accuracy?: AccuracyAndroid
  enableHighAccuracy?: boolean
  distanceFilter?: number
  showLocationDialog?: boolean
  forceRequestLocation?: boolean
  forceLocationManager?: boolean
}

/**
 * Options for watchPosition
 */
export interface GeoWatchOptions {
  accuracy?: AccuracyAndroid
  enableHighAccuracy?: boolean
  distanceFilter?: number
  interval?: number
  fastestInterval?: number
  showLocationDialog?: boolean
  forceRequestLocation?: boolean
  forceLocationManager?: boolean
}

/**
 * Callback for successful position retrieval
 */
export type SuccessCallback = (position: GeoPosition) => void

/**
 * Callback for position errors
 */
export type ErrorCallback = (error: GeoError) => void

/**
 * Nitro Geolocation Hybrid Object
 */
export interface NitroGeolocation extends HybridObject<{ ios: 'swift', android: 'kotlin' }> {
  /**
   * Request location authorization (iOS only)
   * On Android, this always resolves with 'granted' as permissions are handled differently
   * @param level - Authorization level to request: 'whenInUse' or 'always'
   * @returns Promise with the authorization result
   */
  requestAuthorization(level: AuthorizationLevel): Promise<AuthorizationResult>

  /**
   * Get the current position
   * @param options - Configuration options for the request
   * @returns Promise with the current position or error
   */
  getCurrentPosition(options: GeoOptions): Promise<GeoPosition>

  /**
   * Start watching position changes
   * @param options - Configuration options for watching
   * @returns Watch ID to use with clearWatch
   */
  startObserving(options: GeoWatchOptions): void

  /**
   * Stop watching position changes
   */
  stopObserving(): void

  /**
   * Add a position listener
   */
  addPositionListener(callback: SuccessCallback): void

  /**
   * Add an error listener
   */
  addErrorListener(callback: ErrorCallback): void

  /**
   * Remove all listeners
   */
  removeAllListeners(): void
}