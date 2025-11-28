# react-native-nitro-geolocation

A high-performance geolocation library for React Native, built with [Nitro Modules](https://github.com/mrousavy/nitro). This is a modern replacement for react-native-geolocation-service with improved performance and type safety.

## Features

- Get current device location with configurable accuracy
- Watch location changes with customizable update intervals
- Request location authorization (iOS) / check permission status (Android)
- Support for both Google Play Services (FusedLocationProvider) and LocationManager on Android
- Full TypeScript support with strict typing
- Compatible with iOS 13+ and Android API 23+

## Requirements

- React Native 0.76.0 or higher
- iOS 13.0 or higher
- Android API 23 (Android 6.0) or higher
- Google Play Services (optional, for FusedLocationProvider on Android)

## Installation

```bash
npm install react-native-nitro-geolocation react-native-nitro-modules
```

### iOS Setup

Add location usage descriptions to your `Info.plist`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>We need your location to show nearby places</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>We need your location to provide navigation</string>
```

Then run:

```bash
cd ios && pod install
```

### Android Setup

Add permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<!-- For background location (optional) -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

## Usage

### Request Authorization (iOS) / Check Permission (Android)

```typescript
import { NitroGeolocation } from 'react-native-nitro-geolocation'

// Request authorization - on iOS this shows the permission dialog
// On Android, this only checks the current permission status
const result = await NitroGeolocation.requestAuthorization('whenInUse')
// result: 'granted' | 'denied' | 'disabled' | 'restricted'

if (result !== 'granted') {
  // Handle permission denied - on Android, use PermissionsAndroid to request
}
```

### Get Current Position

```typescript
import { NitroGeolocation } from 'react-native-nitro-geolocation'

try {
  const position = await NitroGeolocation.getCurrentPosition({
    enableHighAccuracy: true,
    timeout: 15000,
    maximumAge: 10000,
  })

  console.log('Latitude:', position.coords.latitude)
  console.log('Longitude:', position.coords.longitude)
  console.log('Accuracy:', position.coords.accuracy)
} catch (error) {
  console.error('Error getting location:', error)
}
```

### Watch Position

```typescript
import { NitroGeolocation } from 'react-native-nitro-geolocation'

// Add listeners
NitroGeolocation.addPositionListener((position) => {
  console.log('New position:', position.coords)
})

NitroGeolocation.addErrorListener((error) => {
  console.error('Location error:', error.message)
})

// Start watching
NitroGeolocation.startObserving({
  enableHighAccuracy: true,
  distanceFilter: 10, // meters
  interval: 5000, // Android only: update interval in ms
})

// Stop watching when done
NitroGeolocation.stopObserving()
NitroGeolocation.removeAllListeners()
```

## API Reference

### Types

```typescript
interface GeoPosition {
  coords: {
    latitude: number
    longitude: number
    accuracy: number
    altitude: number | undefined
    altitudeAccuracy: number | undefined
    heading: number | undefined
    speed: number | undefined
  }
  timestamp: number
  provider: string | undefined // e.g., 'fused', 'gps', 'network', 'CoreLocation'
  mocked: boolean | undefined // Android only
}

interface GeoOptions {
  timeout?: number // Request timeout in milliseconds
  maximumAge?: number // Max age of cached location in milliseconds
  enableHighAccuracy?: boolean // Use GPS for higher accuracy
  accuracy?: 'high' | 'balanced' | 'low' | 'passive' // Fine-grained accuracy control
  distanceFilter?: number // Minimum distance (meters) before update
  forceRequestLocation?: boolean // Skip cache, get fresh location
  forceLocationManager?: boolean // Android: force use LocationManager instead of FusedLocationProvider
  showLocationDialog?: boolean // Android: show settings dialog if location disabled
}

interface GeoWatchOptions {
  enableHighAccuracy?: boolean
  accuracy?: 'high' | 'balanced' | 'low' | 'passive'
  distanceFilter?: number
  interval?: number // Android: update interval in milliseconds
  fastestInterval?: number // Android: fastest update interval
  forceRequestLocation?: boolean
  forceLocationManager?: boolean
  showLocationDialog?: boolean
}

type AuthorizationLevel = 'always' | 'whenInUse'
type AuthorizationResult = 'granted' | 'denied' | 'disabled' | 'restricted'
```

### Methods

| Method | Description |
|--------|-------------|
| `requestAuthorization(level)` | Request location authorization. On iOS, shows the permission dialog. On Android, only checks current status. |
| `getCurrentPosition(options)` | Get the current location. Returns a Promise with GeoPosition. |
| `startObserving(options)` | Start watching location changes. Use with addPositionListener. |
| `stopObserving()` | Stop watching location changes. |
| `addPositionListener(callback)` | Add a listener for position updates. |
| `addErrorListener(callback)` | Add a listener for errors. |
| `removeAllListeners()` | Remove all position and error listeners. |

## Accuracy Mapping

| Option | iOS | Android |
|--------|-----|---------|
| `high` | kCLLocationAccuracyBest | PRIORITY_HIGH_ACCURACY |
| `balanced` | kCLLocationAccuracyNearestTenMeters | PRIORITY_BALANCED_POWER_ACCURACY |
| `low` | kCLLocationAccuracyHundredMeters | PRIORITY_LOW_POWER |
| `passive` | kCLLocationAccuracyThreeKilometers | PRIORITY_PASSIVE |

## Error Codes

| Code | Name | Description |
|------|------|-------------|
| 1 | PERMISSION_DENIED | Location permission not granted |
| 2 | POSITION_UNAVAILABLE | Location could not be determined |
| 3 | TIMEOUT | Location request timed out |
| 4 | PLAY_SERVICE_NOT_AVAILABLE | Google Play Services unavailable (Android) |
| 5 | SETTINGS_NOT_SATISFIED | Location settings not satisfied (Android) |
| -1 | INTERNAL_ERROR | Internal error occurred |

## Migration from react-native-geolocation-service

This library is designed as a drop-in replacement with similar API:

```typescript
// Before (react-native-geolocation-service)
import Geolocation from 'react-native-geolocation-service'
Geolocation.getCurrentPosition(successCallback, errorCallback, options)

// After (react-native-nitro-geolocation)
import { NitroGeolocation } from 'react-native-nitro-geolocation'
const position = await NitroGeolocation.getCurrentPosition(options)
```

Key differences:
- Promise-based API instead of callbacks
- Uses Nitro Modules for better performance
- TypeScript-first with strict types

## Credits

- Built with [Nitro Modules](https://github.com/mrousavy/nitro) by Marc Rousavy
- Inspired by [react-native-geolocation-service](https://github.com/Agontuk/react-native-geolocation-service)
- Bootstrapped with [create-nitro-module](https://github.com/nickclaw/create-nitro-module)

## License

MIT
