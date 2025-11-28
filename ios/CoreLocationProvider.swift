//
//  CoreLocationProvider.swift
//  NitroGeolocation
//
//  Created by Rom on 11/26/2025.
//

import Foundation
import CoreLocation
import NitroModules

/**
 * Location provider implementation using CoreLocation.
 * Handles both single location requests and continuous updates.
 */
class CoreLocationProvider: NSObject, LocationProvider {
    
    private static let TAG = "CoreLocationProvider"
    
    private let locationManager: CLLocationManager
    private var startTime: CFAbsoluteTime = 0
    
    // Single location request callbacks
    private var singleUpdateSuccessCallback: ((CLLocation) -> Void)?
    private var singleUpdateErrorCallback: ((GeoError) -> Void)?
    private var singleUpdateOptions: GeoOptions?
    private var singleUpdateTimeoutTimer: Timer?
    private var isWaitingForSingleUpdate = false
    
    // Watch location callbacks
    private var watchSuccessCallback: ((CLLocation) -> Void)?
    private var watchErrorCallback: ((GeoError) -> Void)?
    private var isWatching = false
    
    override init() {
        self.locationManager = CLLocationManager()
        super.init()
        self.locationManager.delegate = self
    }
    
    // MARK: - LocationProvider Protocol
    
    func getCurrentLocation(
        options: GeoOptions,
        onSuccess: @escaping (CLLocation) -> Void,
        onError: @escaping (GeoError) -> Void
    ) {
        self.startTime = CFAbsoluteTimeGetCurrent()
        log("getCurrentLocation started")
        
        // Check if we have a cached location that satisfies maximumAge
        if let cachedLocation = locationManager.location {
            let maximumAge = options.maximumAge ?? 0.0
            let locationAge = getLocationAge(cachedLocation)
            
            if locationAge <= maximumAge {
                log("Using cached location (age: \(String(format: "%.2f", locationAge))ms)")
                onSuccess(cachedLocation)
                logPerformance("getCurrentLocation (cached)")
                return
            }
        }
        
        // Need to request a fresh location
        singleUpdateSuccessCallback = onSuccess
        singleUpdateErrorCallback = onError
        singleUpdateOptions = options
        isWaitingForSingleUpdate = true
        
        // Configure location manager
        configureLocationManager(options: options)
        
        // Set up timeout
        let timeout = options.timeout ?? 30000.0 // Default 30 seconds
        setupTimeoutTimer(timeout: timeout)
        
        // Request location
        log("Requesting fresh location with timeout: \(timeout)ms")
        locationManager.requestLocation()
    }
    
    func requestLocationUpdates(
        options: GeoWatchOptions,
        onLocationChanged: @escaping (CLLocation) -> Void,
        onError: @escaping (GeoError) -> Void
    ) {
        log("requestLocationUpdates started")
        
        // Clean up any existing watch
        if isWatching {
            removeLocationUpdates()
        }
        
        watchSuccessCallback = onLocationChanged
        watchErrorCallback = onError
        isWatching = true
        
        // Configure location manager
        configureLocationManager(watchOptions: options)
        
        // Start continuous updates
        locationManager.startUpdatingLocation()
    }
    
    func removeLocationUpdates() {
        log("removeLocationUpdates")
        
        // Clean up single update
        cancelSingleUpdate()
        
        // Clean up watch
        if isWatching {
            locationManager.stopUpdatingLocation()
            watchSuccessCallback = nil
            watchErrorCallback = nil
            isWatching = false
        }
    }
    
    // MARK: - Private Methods
    
    private func configureLocationManager(options: GeoOptions) {
        // Set accuracy based on enableHighAccuracy flag
        let enableHighAccuracy = options.enableHighAccuracy ?? false
        if enableHighAccuracy {
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
        } else {
            locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        }
        
        // Set distance filter
        if let distanceFilter = options.distanceFilter, distanceFilter > 0 {
            locationManager.distanceFilter = distanceFilter
        } else {
            locationManager.distanceFilter = kCLDistanceFilterNone
        }
        
        log("Configured - accuracy: \(locationManager.desiredAccuracy), distanceFilter: \(locationManager.distanceFilter)")
    }
    
    private func configureLocationManager(watchOptions options: GeoWatchOptions) {
        // Set accuracy based on enableHighAccuracy flag
        let enableHighAccuracy = options.enableHighAccuracy ?? false
        if enableHighAccuracy {
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
        } else {
            locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        }
        
        // Set distance filter
        if let distanceFilter = options.distanceFilter, distanceFilter > 0 {
            locationManager.distanceFilter = distanceFilter
        } else {
            locationManager.distanceFilter = kCLDistanceFilterNone
        }
        
        log("Watch configured - accuracy: \(locationManager.desiredAccuracy), distanceFilter: \(locationManager.distanceFilter)")
    }
    
    private func setupTimeoutTimer(timeout: Double) {
        singleUpdateTimeoutTimer?.invalidate()
        let timeoutSeconds = timeout / 1000.0
        singleUpdateTimeoutTimer = Timer.scheduledTimer(withTimeInterval: timeoutSeconds, repeats: false) { [weak self] _ in
            self?.handleTimeout()
        }
    }
    
    private func handleTimeout() {
        log("Location request timed out")
        guard isWaitingForSingleUpdate else { return }
        
        let error = GeoError(code: .timeout, message: "Location request timed out")
        singleUpdateErrorCallback?(error)
        cancelSingleUpdate()
    }
    
    private func cancelSingleUpdate() {
        singleUpdateTimeoutTimer?.invalidate()
        singleUpdateTimeoutTimer = nil
        singleUpdateSuccessCallback = nil
        singleUpdateErrorCallback = nil
        singleUpdateOptions = nil
        isWaitingForSingleUpdate = false
    }
    
    private func getLocationAge(_ location: CLLocation) -> Double {
        return -location.timestamp.timeIntervalSinceNow * 1000.0 // Convert to milliseconds
    }
    
    private func log(_ message: String) {
        print("[\(CoreLocationProvider.TAG)] \(message)")
    }
    
    private func logPerformance(_ operation: String) {
        let elapsed = (CFAbsoluteTimeGetCurrent() - startTime) * 1000.0
        log("\(operation) completed in \(String(format: "%.2f", elapsed))ms")
    }
}

// MARK: - CLLocationManagerDelegate

extension CoreLocationProvider: CLLocationManagerDelegate {
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        
        log("didUpdateLocations: \(location.coordinate.latitude), \(location.coordinate.longitude) (accuracy: \(location.horizontalAccuracy)m)")
        
        // Handle single update
        if isWaitingForSingleUpdate {
            log("Delivering single location update")
            singleUpdateSuccessCallback?(location)
            logPerformance("getCurrentLocation (fresh)")
            cancelSingleUpdate()
        }
        
        // Handle watch updates
        if isWatching {
            watchSuccessCallback?(location)
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        log("didFailWithError: \(error.localizedDescription)")
        
        let geoError: GeoError
        
        if let clError = error as? CLError {
            switch clError.code {
            case .denied:
                geoError = GeoError(code: .permissionDenied, message: "Location access denied")
            case .locationUnknown:
                geoError = GeoError(code: .positionUnavailable, message: "Location unknown")
            case .network:
                geoError = GeoError(code: .positionUnavailable, message: "Network error")
            default:
                geoError = GeoError(code: .internalError, message: error.localizedDescription)
            }
        } else {
            geoError = GeoError(code: .internalError, message: error.localizedDescription)
        }
        
        // Handle single update error
        if isWaitingForSingleUpdate {
            singleUpdateErrorCallback?(geoError)
            cancelSingleUpdate()
        }
        
        // Handle watch error
        if isWatching {
            watchErrorCallback?(geoError)
        }
    }
    
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        log("Authorization changed: \(status.rawValue)")
        
        switch status {
        case .denied, .restricted:
            let error = GeoError(code: .permissionDenied, message: "Location permission denied")
            
            if isWaitingForSingleUpdate {
                singleUpdateErrorCallback?(error)
                cancelSingleUpdate()
            }
            
            if isWatching {
                watchErrorCallback?(error)
            }
            
        case .authorizedWhenInUse, .authorizedAlways:
            log("Location authorized")
            
        case .notDetermined:
            log("Location authorization not determined")
            
        @unknown default:
            log("Unknown authorization status")
        }
    }
}
