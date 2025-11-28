//
//  LocationProvider.swift
//  NitroGeolocation
//
//  Created by Rom on 11/26/2025.
//

import Foundation
import CoreLocation
import NitroModules

/**
 * Protocol defining the interface for location providers.
 * Provides a flexible abstraction that could support different implementations
 * (e.g., CLLocationManager, mock providers for testing).
 */
protocol LocationProvider: AnyObject {
    /**
     * Get the current location once.
     *
     * - Parameters:
     *   - options: Configuration options for the location request
     *   - onSuccess: Callback invoked with the location when successfully obtained
     *   - onError: Callback invoked with an error if the location request fails
     */
    func getCurrentLocation(
        options: GeoOptions,
        onSuccess: @escaping (CLLocation) -> Void,
        onError: @escaping (GeoError) -> Void
    )
    
    /**
     * Start continuous location updates.
     *
     * - Parameters:
     *   - options: Configuration options for the location updates
     *   - onLocationChanged: Callback invoked each time a new location is received
     *   - onError: Callback invoked when an error occurs
     */
    func requestLocationUpdates(
        options: GeoWatchOptions,
        onLocationChanged: @escaping (CLLocation) -> Void,
        onError: @escaping (GeoError) -> Void
    )
    
    /**
     * Stop receiving location updates.
     */
    func removeLocationUpdates()
}
