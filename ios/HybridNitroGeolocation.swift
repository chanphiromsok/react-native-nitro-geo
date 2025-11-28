//
//  HybridNitroGeolocation.swift
//  NitroGeolocation
//
//  Created by Rom on 11/26/2025.
//

import Foundation
import CoreLocation
import NitroModules

/**
 * Delegate class to handle authorization status changes
 */
class AuthorizationDelegate: NSObject, CLLocationManagerDelegate {
    private let completion: (CLAuthorizationStatus) -> Void
    
    init(completion: @escaping (CLAuthorizationStatus) -> Void) {
        self.completion = completion
        super.init()
    }
    
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        // Only call completion if status is determined
        if status != .notDetermined {
            completion(status)
        }
    }
}

/**
 * Main HybridObject for NitroGeolocation.
 * Implements the Nitro protocol and delegates to the location provider.
 */
class HybridNitroGeolocation: HybridNitroGeolocationSpec {
    
    private static let TAG = "NitroGeolocation"
    
    // Cached location provider - reused for better performance
    private var locationProvider: CoreLocationProvider?
    
    // Listener callbacks
    private var positionListeners: [(GeoPosition) -> Void] = []
    private var errorListeners: [(GeoError) -> Void] = []
    
    // MARK: - Initialization
    
    override init() {
        super.init()
        log("HybridNitroGeolocation initialized")
    }
    
    deinit {
        cleanup()
        log("HybridNitroGeolocation deinitialized")
    }
    
    // MARK: - Private Methods
    
    /**
     * Get or create the location provider.
     * Provider is cached and reused for better performance.
     */
    private func getLocationProvider() -> CoreLocationProvider {
        if let provider = locationProvider {
            return provider
        }
        
        let provider = CoreLocationProvider()
        locationProvider = provider
        log("Created CoreLocationProvider")
        return provider
    }
    
    private func log(_ message: String) {
        print("[\(HybridNitroGeolocation.TAG)] \(message)")
    }
    
    private func notifyPosition(_ position: GeoPosition) {
        DispatchQueue.main.async { [weak self] in
            self?.positionListeners.forEach { listener in
                listener(position)
            }
        }
    }
    
    private func notifyError(_ error: GeoError) {
        DispatchQueue.main.async { [weak self] in
            self?.errorListeners.forEach { listener in
                listener(error)
            }
        }
    }
    
    /**
     * Clean up all resources.
     */
    private func cleanup() {
        log("Cleaning up NitroGeolocation")
        
        try? stopObserving()
        try? removeAllListeners()
        
        locationProvider?.removeLocationUpdates()
        locationProvider = nil
    }
    
    // MARK: - HybridNitroGeolocationSpec Protocol
    
    func requestAuthorization(level: AuthorizationLevel) throws -> Promise<AuthorizationResult> {
        log("requestAuthorization called with level: \(level)")
        
        let promise = Promise<AuthorizationResult>()
        let locationManager = CLLocationManager()
        
        // Check if location services are enabled
        if !CLLocationManager.locationServicesEnabled() {
            log("Location services disabled")
            promise.resolve(withResult: .disabled)
            return promise
        }
        
        // Check current authorization status
        let currentStatus = locationManager.authorizationStatus
        
        switch currentStatus {
        case .authorizedAlways:
            log("Already authorized always")
            promise.resolve(withResult: .granted)
            
        case .authorizedWhenInUse:
            if level == .wheninuse {
                log("Already authorized when in use")
                promise.resolve(withResult: .granted)
            } else {
                // Need to upgrade to always - request it
                log("Need to upgrade to always authorization")
                requestAuthorizationInternal(level: level, locationManager: locationManager, promise: promise)
            }
            
        case .denied:
            log("Authorization denied")
            promise.resolve(withResult: .denied)
            
        case .restricted:
            log("Authorization restricted")
            promise.resolve(withResult: .restricted)
            
        case .notDetermined:
            log("Authorization not determined, requesting...")
            requestAuthorizationInternal(level: level, locationManager: locationManager, promise: promise)
            
        @unknown default:
            log("Unknown authorization status")
            promise.resolve(withResult: .denied)
        }
        
        return promise
    }
    
    private var authorizationDelegate: AuthorizationDelegate?
    
    private func requestAuthorizationInternal(
        level: AuthorizationLevel,
        locationManager: CLLocationManager,
        promise: Promise<AuthorizationResult>
    ) {
        // Create a delegate to handle the authorization callback
        let delegate = AuthorizationDelegate { [weak self] status in
            self?.log("Authorization callback received: \(status.rawValue)")
            
            switch status {
            case .authorizedAlways:
                promise.resolve(withResult: .granted)
            case .authorizedWhenInUse:
                if level == .wheninuse {
                    promise.resolve(withResult: .granted)
                } else {
                    promise.resolve(withResult: .denied)
                }
            case .denied:
                promise.resolve(withResult: .denied)
            case .restricted:
                promise.resolve(withResult: .restricted)
            case .notDetermined:
                // Shouldn't happen after requesting
                promise.resolve(withResult: .denied)
            @unknown default:
                promise.resolve(withResult: .denied)
            }
            
            // Clean up the delegate
            self?.authorizationDelegate = nil
        }
        
        // Keep a strong reference to the delegate
        authorizationDelegate = delegate
        locationManager.delegate = delegate
        
        // Request the appropriate authorization
        if level == .always {
            locationManager.requestAlwaysAuthorization()
        } else {
            locationManager.requestWhenInUseAuthorization()
        }
    }
    
    func getCurrentPosition(options: GeoOptions) throws -> Promise<GeoPosition> {
        log("getCurrentPosition called")
        
        // Check permissions
        if !LocationUtils.hasLocationPermission() {
            log("Location permission not granted")
            return Promise.rejected(withError: RuntimeError.error(withMessage: "Location permission not granted"))
        }
        
        let provider = getLocationProvider()
        let promise = Promise<GeoPosition>()
        
        provider.getCurrentLocation(
            options: options,
            onSuccess: { location in
                let position = LocationUtils.locationToGeoPosition(location)
                promise.resolve(withResult: position)
            },
            onError: { error in
                promise.reject(withError: RuntimeError.error(withMessage: error.message))
            }
        )
        
        return promise
    }
    
    func startObserving(options: GeoWatchOptions) throws {
        log("startObserving called")
        
        // Check permissions
        if !LocationUtils.hasLocationPermission() {
            log("Location permission not granted")
            notifyError(GeoError(code: .permissionDenied, message: "Location permission not granted"))
            return
        }
        
        // Stop any existing observation first
        try? stopObserving()
        
        let provider = getLocationProvider()
        
        provider.requestLocationUpdates(
            options: options,
            onLocationChanged: { [weak self] location in
                let position = LocationUtils.locationToGeoPosition(location)
                self?.notifyPosition(position)
            },
            onError: { [weak self] error in
                self?.notifyError(error)
            }
        )
    }
    
    func stopObserving() throws {
        log("stopObserving called")
        locationProvider?.removeLocationUpdates()
    }
    
    func addPositionListener(callback: @escaping (GeoPosition) -> Void) throws {
        log("addPositionListener called")
        positionListeners.append(callback)
    }
    
    func addErrorListener(callback: @escaping (GeoError) -> Void) throws {
        log("addErrorListener called")
        errorListeners.append(callback)
    }
    
    func removeAllListeners() throws {
        log("removeAllListeners called")
        positionListeners.removeAll()
        errorListeners.removeAll()
        // Also stop observing when all listeners are removed
        try? stopObserving()
    }
}
