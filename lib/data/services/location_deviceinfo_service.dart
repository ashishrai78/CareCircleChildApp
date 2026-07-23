<<<<<<< HEAD
import 'package:geolocator/geolocator.dart';
import 'package:battery_plus/battery_plus.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:get_storage/get_storage.dart';

class LocationService {
  final Battery _battery = Battery();
  final DeviceInfoPlugin _deviceInfo = DeviceInfoPlugin();
  String? _deviceName;

  Future<void> initDeviceInfo() async {
    try {
      final androidInfo = await _deviceInfo.androidInfo;
      _deviceName = "${androidInfo.brand} ${androidInfo.model}";
    } catch (e) {
      _deviceName = "Unknown Device";
      print("❌ Device info error: $e");
    }
  }

  Future<Map<String, dynamic>> collectData() async {
    try {
      // Location
      Position? position;
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.always || permission == LocationPermission.whileInUse) {
        try {
          position = await Geolocator.getCurrentPosition();
        } catch (e) {
          print("⚠️ Location error: $e");
        }
      }

      // Battery
      int batteryLevel = await _battery.batteryLevel;
      BatteryState state = await _battery.batteryState;

      Map<String, dynamic> data = {
        "battery": batteryLevel,
        "isCharging": state == BatteryState.charging,
        "device": _deviceName ?? "Unknown",
        "timestamp": FieldValue.serverTimestamp(),
      };

      if (position != null) {
        data["lat"] = position.latitude;
        data["lng"] = position.longitude;
=======
import 'dart:async';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/services.dart';
import 'package:get_storage/get_storage.dart';

/// 📍 PRODUCTION Location + Device Info Service
///
/// Uses NATIVE Method Channel (LocationProvider.kt + DeviceInfoProvider.kt)
/// instead of geolocator + battery_plus + device_info_plus packages.
///
/// Benefits:
///  - More accurate (FusedLocationProvider)
///  - More data (speed, accuracy, altitude, address, mock detection, etc.)
///  - Lower battery (native batches requests)
///  - Single channel call returns all device info
class LocationService {
  static const _locationChannel = MethodChannel('location_channel');
  static const _deviceChannel = MethodChannel('device_channel');

  String? _cachedDeviceName;
  Map<String, dynamic>? _cachedDeviceInfo;

  /// Initialize device info (called once on service start)
  Future<void> initDeviceInfo() async {
    try {
      final deviceInfo = await _deviceChannel.invokeMethod<Map>('getDeviceInfo');
      _cachedDeviceInfo = deviceInfo?.cast<String, dynamic>();
      _cachedDeviceName = _cachedDeviceInfo != null
          ? "${_cachedDeviceInfo!['brand']} ${_cachedDeviceInfo!['model']}"
          : "Unknown Device";
    } catch (e) {
      print("❌ Device info init failed: $e");
      _cachedDeviceName = "Unknown Device";
    }
  }

  /// Collect location + device + battery + network data in one shot
  Future<Map<String, dynamic>> collectData() async {
    try {
      // Run all in parallel
      final results = await Future.wait([
        _safeChannelCall(() => _locationChannel.invokeMethod<Map>('getLocation', {
              'highAccuracy': true,
              'timeoutMs': 10000,
            })),
        _safeChannelCall(() => _deviceChannel.invokeMethod<Map>('getBatteryInfo')),
        _safeChannelCall(() => _deviceChannel.invokeMethod<Map>('getNetworkInfo')),
        _safeChannelCall(() => _deviceChannel.invokeMethod<Map>('getStorageInfo')),
        _safeChannelCall(() => _deviceChannel.invokeMethod<Map>('getMemoryInfo')),
      ]);

      final locationData = results[0]?.cast<String, dynamic>();
      final batteryData = results[1]?.cast<String, dynamic>();
      final networkData = results[2]?.cast<String, dynamic>();
      final storageData = results[3]?.cast<String, dynamic>();
      final memoryData = results[4]?.cast<String, dynamic>();

      final Map<String, dynamic> data = {
        // Device (cached)
        "device": _cachedDeviceName ?? "Unknown",
        "osVersion": _cachedDeviceInfo?['osVersion'],
        "sdkVersion": _cachedDeviceInfo?['sdkVersion'],
        "buildNumber": _cachedDeviceInfo?['buildNumber'],
        "androidId": _cachedDeviceInfo?['androidId'],
        "uptimeMs": _cachedDeviceInfo?['uptimeMs'],
        "rooted": _cachedDeviceInfo?['rooted'] ?? false,

        // Battery
        "battery": batteryData?['level'] ?? 0,
        "isCharging": batteryData?['isCharging'] ?? false,
        "batteryTemp": batteryData?['temperature'],
        "batteryVoltage": batteryData?['voltage'],
        "batteryHealth": batteryData?['health'],
        "powerSource": batteryData?['powerSource'],

        // Network
        "networkType": networkData?['type'],
        "carrier": networkData?['carrier'],
        "wifiSsid": networkData?['wifiSsid'],
        "ip": networkData?['ip'],
        "hasInternet": networkData?['hasInternet'] ?? false,

        // Storage
        "storageTotalMB": storageData?['internalTotalMB'],
        "storageAvailableMB": storageData?['internalAvailableMB'],
        "storageUsedPct": storageData?['internalUsedPercentage'],

        // Memory
        "ramTotalMB": memoryData?['totalMB'],
        "ramAvailableMB": memoryData?['availableMB'],
        "ramUsedPct": memoryData?['usedPercentage'],

        "timestamp": FieldValue.serverTimestamp(),
      };

      // Location (optional — may fail if permission denied)
      if (locationData != null) {
        data["lat"] = locationData['lat'];
        data["lng"] = locationData['lng'];
        data["accuracy"] = locationData['accuracy'];
        data["altitude"] = locationData['altitude'];
        data["speed"] = locationData['speed'];
        data["bearing"] = locationData['bearing'];
        data["isMock"] = locationData['isMock'] ?? false;
        data["address"] = locationData['address'];
        data["locationProvider"] = locationData['provider'];
>>>>>>> workspace
      }

      return data;
    } catch (e) {
      print("❌ Location collection error: $e");
      rethrow;
    }
  }

<<<<<<< HEAD
  Future<void> saveData(Map<String, dynamic> data) async {
    try {
      final docId = GetStorage().read('currentUserId');
=======
  /// Quick status (battery only) — for heartbeat
  Future<Map<String, dynamic>> getQuickStatus() async {
    try {
      final batteryData = await _deviceChannel.invokeMethod<Map>('getBatteryInfo');
      return {
        'battery': batteryData?['level'] ?? 0,
        'isCharging': batteryData?['isCharging'] ?? false,
      };
    } catch (_) {
      return {'battery': -1, 'isCharging': false};
    }
  }

  /// Save to Firestore
  Future<void> saveData(Map<String, dynamic> data) async {
    try {
      final docId = GetStorage().read('currentUserId');
      if (docId == null) throw Exception("currentUserId missing");

>>>>>>> workspace
      await FirebaseFirestore.instance
          .collection("child_live_data")
          .doc(docId)
          .set(data, SetOptions(merge: true));
      print("✅ Location & device data saved");
<<<<<<< HEAD
      print('Current User ${docId}');
=======
>>>>>>> workspace
    } catch (e) {
      print("❌ Firebase save error (location): $e");
      rethrow;
    }
  }
<<<<<<< HEAD
}
=======

  // ============ Helpers ============
  Future<Map?> _safeChannelCall(Future<Map?> Function() fn) async {
    try {
      return await fn().timeout(const Duration(seconds: 15));
    } catch (e) {
      print("⚠️ Channel call failed: $e");
      return null;
    }
  }
}
>>>>>>> workspace
