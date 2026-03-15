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
    } catch (e) {
      print("❌ Screen time collection error: $e");
      rethrow;
    }
  }

  Future<void> saveData(Map<String, dynamic> data, String dateKey) async {
    try {
      final docId = GetStorage().read('currentUserId');
      await FirebaseFirestore.instance
          .collection("usage_data")
          .doc(docId)
          .collection("daily")
          .doc(dateKey)
          .set({
        "totalTime": data["totalTime"],
        "apps": data["apps"],
        "updatedAt": data["updatedAt"],
      }, SetOptions(merge: true));
      print("✅ Screen time saved for $dateKey");
    } catch (e) {
      print("❌ Firebase save error (screen time): $e");
      rethrow;
    }
  }
}