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
        
        // Check if location services are enabled first
        if !CLLocationManager.locationServicesEnabled() {
            log("Location services disabled")
            return Promise.resolved(withResult: .disabled)
        }
        
        // Check current authorization status (iOS 13 compatible)
        let currentStatus: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            currentStatus = CLLocationManager().authorizationStatus
        } else {
            currentStatus = CLLocationManager.authorizationStatus()
        }
        
        // Determine result from current status
        let immediateResult: AuthorizationResult? = {
            switch currentStatus {
            case .authorizedAlways:
                log("Already authorized always")
                return .granted
                
            case .authorizedWhenInUse:
                if level == .wheninuse {
                    log("Already authorized when in use")
                    return .granted
                }
                return nil // Need to upgrade to always
                
            case .denied:
                log("Authorization denied")
                return .denied
                
            case .restricted:
                log("Authorization restricted")
                return .restricted
                
            case .notDetermined:
                return nil // Need to request
                
            @unknown default:
                log("Unknown authorization status")
                return .denied
            }
        }()
        
        // Return immediately if we have a result
        if let result = immediateResult {
            return Promise.resolved(withResult: result)
        }
        
        // Need to request authorization - use async pattern
        log("Requesting authorization...")
        return Promise.async { [weak self] in
            try await withCheckedThrowingContinuation { continuation in
                self?.requestAuthorizationWithContinuation(level: level, continuation: continuation)
            }
        }
    }
    
    private var authorizationDelegate: AuthorizationDelegate?
    private var authorizationLocationManager: CLLocationManager? // Keep strong reference to prevent deallocation
    
    private func requestAuthorizationWithContinuation(
        level: AuthorizationLevel,
        continuation: CheckedContinuation<AuthorizationResult, Error>
    ) {
        let locationManager = CLLocationManager()
        
        // Create a delegate to handle the authorization callback
        let delegate = AuthorizationDelegate { [weak self] status in
            self?.log("Authorization callback received: \(status.rawValue)")
            
            let result: AuthorizationResult
            switch status {
            case .authorizedAlways:
                result = .granted
            case .authorizedWhenInUse:
                result = (level == .wheninuse) ? .granted : .denied
            case .denied:
                result = .denied
            case .restricted:
                result = .restricted
            case .notDetermined:
                result = .denied
            @unknown default:
                result = .denied
            }
            
            // Clean up references
            self?.authorizationDelegate = nil
            self?.authorizationLocationManager = nil
            
            continuation.resume(returning: result)
        }
        
        // Keep strong references to prevent deallocation before callback
        authorizationDelegate = delegate
        authorizationLocationManager = locationManager
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
        
        // Check permissions first - return rejected promise
        if !LocationUtils.hasLocationPermission() {
            log("Location permission not granted")
            return Promise.rejected(withError: RuntimeError.error(withMessage: "Location permission not granted. Call requestAuthorization() first."))
        }
        
        let provider = getLocationProvider()
        
        return Promise.async {
            try await withCheckedThrowingContinuation { continuation in
                provider.getCurrentLocation(
                    options: options,
                    onSuccess: { location in
                        let position = LocationUtils.locationToGeoPosition(location)
                        continuation.resume(returning: position)
                    },
                    onError: { error in
                        continuation.resume(throwing: RuntimeError.error(withMessage: error.message))
                    }
                )
            }
        }
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
