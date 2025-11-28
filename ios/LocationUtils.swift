//
//  LocationUtils.swift
//  NitroGeolocation
//
//  Created by Rom on 11/26/2025.
//

import Foundation
import CoreLocation
import NitroModules

/**
 * Utility functions for location operations.
 */
struct LocationUtils {
    
    /**
     * Check if location services are enabled and authorized.
     *
     * - Returns: true if location services are available
     */
    static func hasLocationPermission() -> Bool {
        guard CLLocationManager.locationServicesEnabled() else {
            return false
        }
        
        let status = getAuthorizationStatus()
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }
    
    /**
     * Get the authorization status for location services.
     * Compatible with iOS 13 and iOS 14+
     *
     * - Returns: The current authorization status
     */
    static func getAuthorizationStatus() -> CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return CLLocationManager().authorizationStatus
        } else {
            return CLLocationManager.authorizationStatus()
        }
    }
    
    /**
     * Check if location services are enabled on the device.
     *
     * - Returns: true if location services are enabled
     */
    static func isLocationServicesEnabled() -> Bool {
        return CLLocationManager.locationServicesEnabled()
    }
    
    /**
     * Get the age of a location in milliseconds.
     *
     * - Parameter location: The location to check
     * - Returns: Age in milliseconds
     */
    static func getLocationAge(_ location: CLLocation) -> Double {
        return -location.timestamp.timeIntervalSinceNow * 1000.0
    }
    
    /**
     * Convert a CLLocation to a GeoPosition struct.
     *
     * - Parameter location: The CoreLocation location object
     * - Returns: A GeoPosition struct suitable for returning to JavaScript
     */
    static func locationToGeoPosition(_ location: CLLocation) -> GeoPosition {
        let coords = GeoCoordinates(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            accuracy: location.horizontalAccuracy,
            altitude: location.altitude,
            altitudeAccuracy: location.verticalAccuracy >= 0 ? location.verticalAccuracy : nil,
            heading: location.course >= 0 ? location.course : nil,
            speed: location.speed >= 0 ? location.speed : nil
        )
        
        let timestamp = location.timestamp.timeIntervalSince1970 * 1000.0 // Convert to milliseconds
        
        return GeoPosition(
            coords: coords,
            timestamp: timestamp,
            provider: "CoreLocation",
            mocked: false
        )
    }
}
