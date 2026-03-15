import 'dart:convert';
import 'package:get_storage/get_storage.dart';
import 'package:installed_apps/installed_apps.dart';
import 'package:cloud_firestore/cloud_firestore.dart';

class InstalledAppsService {
  Future<Map<String, dynamic>> collectData() async {
    try {
      final apps = await InstalledApps.getInstalledApps(
        excludeSystemApps: true,
        excludeNonLaunchableApps: true,
        withIcon: true,
      );

      Map<String, dynamic> appsMapList = {};

      for (final app in apps) {
        String? base64Icon;
        if (app.icon != null) {
          base64Icon = base64Encode(app.icon!);
        }

        appsMapList[app.packageName] = {
          "name": app.name,
          "icon": base64Icon,
          "category": app.category.name,
          "installedAt": app.installedTimestamp,
        };
      }

      return {
        "apps": appsMapList,
        "updatedAt": FieldValue.serverTimestamp(),
      };
    } catch (e) {
      print("❌ Installed apps collection error: $e");
      rethrow;
    }
  }

  Future<void> saveData(Map<String, dynamic> data) async {
    try {
      final docId = GetStorage().read('currentUserId');
      await FirebaseFirestore.instance
          .collection("installed_apps")
          .doc(docId)
          .set(data, SetOptions(merge: true));
      print("✅ Installed apps saved");
    } catch (e) {
      print("❌ Firebase save error (installed apps): $e");
      rethrow;
    }
  }
}