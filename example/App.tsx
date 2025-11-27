import React, { useState, useEffect } from 'react';
import { Text, View, StyleSheet, Button, Platform, PermissionsAndroid, Alert, ScrollView } from 'react-native';
import { Geolocation, GeoPosition, PositionError } from 'react-native-nitro-geolocation';

function App(): React.JSX.Element {
  const [location, setLocation] = useState<GeoPosition | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [watchId, setWatchId] = useState<number | null>(null);
  const [isWatching, setIsWatching] = useState(false);

  // Request permissions on Android
  const requestLocationPermission = async (): Promise<boolean> => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
          {
            title: 'Location Permission',
            message: 'This app needs access to your location.',
            buttonNeutral: 'Ask Me Later',
            buttonNegative: 'Cancel',
            buttonPositive: 'OK',
          }
        );
        return granted === PermissionsAndroid.RESULTS.GRANTED;
      } catch (err) {
        console.warn(err);
        return false;
      }
    }
    return true;
  };

  const getCurrentPosition = async () => {
    const hasPermission = await requestLocationPermission();
    if (!hasPermission) {
      setError('Location permission denied');
      return;
    }

    setError(null);
    Geolocation.getCurrentPosition(
      (position) => {
        console.log('Position:', position);
        setLocation(position);
        setError(null);
      },
      (err) => {
        console.log('Error:', err);
        setError(`Error ${err.code}: ${err.message}`);
      },
      {
        enableHighAccuracy: true,
        timeout: 15000,
        maximumAge: 10000,
      }
    );
  };

  const startWatching = async () => {
    const hasPermission = await requestLocationPermission();
    if (!hasPermission) {
      setError('Location permission denied');
      return;
    }

    setError(null);
    const id = Geolocation.watchPosition(
      (position) => {
        console.log('Watch Position:', position);
        setLocation(position);
        setError(null);
      },
      (err) => {
        console.log('Watch Error:', err);
        setError(`Error ${err.code}: ${err.message}`);
      },
      {
        enableHighAccuracy: true,
        distanceFilter: 10,
        interval: 5000,
        fastestInterval: 2000,
      }
    );
    setWatchId(id);
    setIsWatching(true);
  };

  const stopWatching = () => {
    if (watchId !== null) {
      Geolocation.clearWatch(watchId);
      setWatchId(null);
      setIsWatching(false);
    }
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (watchId !== null) {
        Geolocation.clearWatch(watchId);
      }
    };
  }, [watchId]);

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Nitro Geolocation Demo</Text>
      
      <View style={styles.buttonContainer}>
        <Button title="Get Current Position" onPress={getCurrentPosition} />
      </View>
      
      <View style={styles.buttonContainer}>
        {!isWatching ? (
          <Button title="Start Watching" onPress={startWatching} />
        ) : (
          <Button title="Stop Watching" onPress={stopWatching} color="red" />
        )}
      </View>

      {error && (
        <View style={styles.errorContainer}>
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      {location && (
        <View style={styles.locationContainer}>
          <Text style={styles.sectionTitle}>📍 Location</Text>
          <Text style={styles.text}>Latitude: {location.coords.latitude.toFixed(6)}</Text>
          <Text style={styles.text}>Longitude: {location.coords.longitude.toFixed(6)}</Text>
          <Text style={styles.text}>Accuracy: {location.coords.accuracy.toFixed(1)} m</Text>
          {location.coords.altitude !== undefined && (
            <Text style={styles.text}>Altitude: {location.coords.altitude.toFixed(1)} m</Text>
          )}
          {location.coords.speed !== undefined && (
            <Text style={styles.text}>Speed: {location.coords.speed.toFixed(1)} m/s</Text>
          )}
          {location.coords.heading !== undefined && (
            <Text style={styles.text}>Heading: {location.coords.heading.toFixed(1)}°</Text>
          )}
          <Text style={styles.text}>Provider: {location.provider ?? 'Unknown'}</Text>
          <Text style={styles.text}>Mocked: {location.mocked ? 'Yes' : 'No'}</Text>
          <Text style={styles.textSmall}>
            Timestamp: {new Date(location.timestamp).toLocaleString()}
          </Text>
        </View>
      )}

      <View style={styles.infoContainer}>
        <Text style={styles.infoText}>
          {isWatching ? '🔴 Watching position...' : '⚪ Not watching'}
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flexGrow: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#f5f5f5',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 30,
    color: '#333',
  },
  buttonContainer: {
    marginVertical: 10,
    width: '80%',
  },
  locationContainer: {
    backgroundColor: 'white',
    padding: 20,
    borderRadius: 10,
    marginTop: 20,
    width: '100%',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 10,
    color: '#333',
  },
  text: {
    fontSize: 16,
    color: '#666',
    marginBottom: 5,
  },
  textSmall: {
    fontSize: 12,
    color: '#999',
    marginTop: 10,
  },
  errorContainer: {
    backgroundColor: '#ffebee',
    padding: 15,
    borderRadius: 10,
    marginTop: 20,
    width: '100%',
  },
  errorText: {
    color: '#c62828',
    fontSize: 14,
  },
  infoContainer: {
    marginTop: 20,
  },
  infoText: {
    fontSize: 14,
    color: '#666',
  },
});

export default App;