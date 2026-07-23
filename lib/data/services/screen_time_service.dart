<<<<<<< HEAD
import 'package:app_usage/app_usage.dart';
import 'package:get_storage/get_storage.dart';
import 'package:intl/intl.dart';
import 'package:cloud_firestore/cloud_firestore.dart';

class ScreenTimeService {
  Future<Map<String, dynamic>> collectData() async {
    try {
      final now = DateTime.now();
      final midnight = DateTime(now.year, now.month, now.day);
      final dateKey = DateFormat('dd-MM-yyyy').format(now);

      final usage = await AppUsage().getAppUsage(midnight, now);

      Map<String, dynamic> appsMap = {};
      int totalScreenTime = 0;

      for (final app in usage) {
        final package = app.packageName;
        final duration = app.usage.inMilliseconds;

        if (duration < 5000) continue;

        if (package.startsWith("com.android") ||
            package.startsWith("android") ||
            package.startsWith("com.google.android.gms") ||
            package.contains("launcher") ||
            package.contains("settings")) {
          continue;
        }

        appsMap[package] = duration;
        totalScreenTime += duration;
      }

      return {
        "dateKey": dateKey,
        "totalTime": totalScreenTime,
        "apps": appsMap,
        "updatedAt": FieldValue.serverTimestamp(),
      };
=======
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/services.dart';
import 'package:get_storage/get_storage.dart';

/// 📊 PRODUCTION Screen Time Service — uses NATIVE UsageStatsProvider
///
/// Why this is better than app_usage package:
///  - Uses UsageStatsManager.queryEvents() (raw events) not queryUsageStats (aggregated buckets)
///  - Returns hourly breakdown, session count, screen on/off events
///  - More accurate on Xiaomi MIUI, Huawei EMUI (which disable default aggregation)
class ScreenTimeService {
  static const _usageChannel = MethodChannel('usage_channel');

  Future<Map<String, dynamic>> collectData() async {
    try {
      final result = await _usageChannel
          .invokeMethod<Map>('getTodayUsage')
          .timeout(const Duration(seconds: 10));

      if (result == null) {
        throw Exception("Native usage call returned null");
      }

      final data = Map<String, dynamic>.from(result);

      // Check for error from native side
      if (data.containsKey('error')) {
        throw Exception(data['error']);
      }

      return data;
>>>>>>> workspace
    } catch (e) {
      print("❌ Screen time collection error: $e");
      rethrow;
    }
  }

<<<<<<< HEAD
  Future<void> saveData(Map<String, dynamic> data, String dateKey) async {
    try {
      final docId = GetStorage().read('currentUserId');
=======
  /// Get screen on/off + unlock events for a day
  Future<Map<String, dynamic>> getScreenEvents({
    DateTime? start,
    DateTime? end,
  }) async {
    try {
      final now = DateTime.now();
      final midnight = DateTime(now.year, now.month, now.day);
      final startMs = start?.millisecondsSinceEpoch ?? midnight.millisecondsSinceEpoch;
      final endMs = end?.millisecondsSinceEpoch ?? now.millisecondsSinceEpoch;

      final result = await _usageChannel.invokeMethod<Map>('getScreenEvents', {
        'startMs': startMs,
        'endMs': endMs,
      });

      return Map<String, dynamic>.from(result ?? {});
    } catch (e) {
      print("❌ Screen events error: $e");
      rethrow;
    }
  }

  /// Check if usage stats permission is granted
  Future<bool> isPermissionGranted() async {
    try {
      final result = await _usageChannel.invokeMethod<bool>('isUsageStatsEnabled');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Open usage access settings
  Future<void> openPermissionSettings() async {
    try {
      await _usageChannel.invokeMethod('openUsageStatsSettings');
    } catch (e) {
      print("❌ Failed to open settings: $e");
    }
  }

  /// Save to Firestore — write to daily subcollection
  Future<void> saveData(Map<String, dynamic> data, String? dateKey) async {
    try {
      final docId = GetStorage().read('currentUserId');
      if (docId == null) throw Exception("currentUserId missing");

      if (dateKey == null) {
        dateKey = data['dateKey'];
      }

>>>>>>> workspace
      await FirebaseFirestore.instance
          .collection("usage_data")
          .doc(docId)
          .collection("daily")
          .doc(dateKey)
          .set({
        "totalTime": data["totalTime"],
        "apps": data["apps"],
<<<<<<< HEAD
        "updatedAt": data["updatedAt"],
      }, SetOptions(merge: true));
      print("✅ Screen time saved for $dateKey");
=======
        "hourlyBreakdown": data["hourlyBreakdown"],
        "sessionCount": data["sessionCount"],
        "updatedAt": FieldValue.serverTimestamp(),
      }, SetOptions(merge: true));
      print("✅ Screen time saved for $dateKey (sessions: ${data["sessionCount"]})");
>>>>>>> workspace
    } catch (e) {
      print("❌ Firebase save error (screen time): $e");
      rethrow;
    }
  }
<<<<<<< HEAD
}
=======
}
>>>>>>> workspace
