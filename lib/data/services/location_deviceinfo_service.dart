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
      }

      return data;
    } catch (e) {
      print("❌ Location collection error: $e");
      rethrow;
    }
  }

  Future<void> saveData(Map<String, dynamic> data) async {
    try {
      final docId = GetStorage().read('currentUserId');
      await FirebaseFirestore.instance
          .collection("child_live_data")
          .doc(docId)
          .set(data, SetOptions(merge: true));
      print("✅ Location & device data saved");
      print('Current User ${docId}');
    } catch (e) {
      print("❌ Firebase save error (location): $e");
      rethrow;
    }
  }
}