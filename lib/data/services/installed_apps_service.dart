import 'dart:convert';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_storage/firebase_storage.dart';
import 'package:flutter/services.dart';
import 'package:get_storage/get_storage.dart';

/// 📱 PRODUCTION Installed Apps Service — NATIVE + Icons to Storage
///
/// Critical fix vs original:
///  - Original stored icons as base64 in Firestore → 100 apps × 100KB = 10MB → CRASHES (1MB limit)
///  - New: Uploads icons to Firebase Storage, saves only URLs in Firestore
///  - Native PackageManager gives version, install source, category — all in one call
class InstalledAppsService {
  static const _appsChannel = MethodChannel('apps_channel');

  Future<Map<String, dynamic>> collectData() async {
    try {
      // Call native — get apps WITHOUT icons first (fast)
      final result = await _appsChannel.invokeMethod<List>('getInstalledApps', {
        'withIcons': false,
        'excludeSystem': true,
      }).timeout(const Duration(seconds: 30));

      if (result == null) {
        throw Exception("Native call returned null");
      }

      final docId = GetStorage().read('currentUserId');
      if (docId == null) throw Exception("currentUserId missing");

      final Map<String, dynamic> appsMap = {};

      for (final rawApp in result) {
        final app = Map<String, dynamic>.from(rawApp);
        final packageName = app['packageName'] as String;

        appsMap[packageName] = {
          "name": app['name'],
          "versionName": app['versionName'],
          "versionCode": app['versionCode'],
          "category": app['category'],
          "systemApp": app['systemApp'],
          "installedAt": app['installedAt'],
          "updatedAt": app['updatedAt'],
          "enabled": app['enabled'],
          "minSdk": app['minSdk'],
          "targetSdk": app['targetSdk'],
          // iconUrl will be added below (only upload if app is new or updated)
        };
      }

      return {
        "apps": appsMap,
        "appCount": appsMap.length,
        "updatedAt": FieldValue.serverTimestamp(),
      };
    } catch (e) {
      print("❌ Installed apps collection error: $e");
      rethrow;
    }
  }

  /// Upload icons to Firebase Storage and update Firestore with URLs
  /// Call this as a background task (not in the main sync loop)
  Future<void> uploadIconsIfNeeded() async {
    try {
      final docId = GetStorage().read('currentUserId');
      if (docId == null) return;

      // Get apps with icons
      final result = await _appsChannel.invokeMethod<List>('getInstalledApps', {
        'withIcons': true,
        'excludeSystem': true,
      }).timeout(const Duration(seconds: 60));

      if (result == null) return;

      final storage = FirebaseStorage.instance;
      final firestore = FirebaseFirestore.instance;

      for (final rawApp in result) {
        final app = Map<String, dynamic>.from(rawApp);
        final packageName = app['packageName'] as String;
        final iconBase64 = app['icon'] as String?;

        if (iconBase64 == null || iconBase64.isEmpty) continue;

        // Check if icon already uploaded
        final iconRef = storage.ref().child('app_icons/$docId/$packageName.webp');

        try {
          // Try to get metadata — if exists, skip upload
          await iconRef.getMetadata();
          continue;
        } catch (_) {
          // Doesn't exist — upload
        }

        // Upload icon
        final bytes = _decodeBase64(iconBase64);
        final uploadTask = await iconRef.putData(
          bytes,
          SettableMetadata(contentType: 'image/webp'),
        );

        final url = await uploadTask.ref.getDownloadURL();

        // Update Firestore with icon URL
        await firestore
            .collection("installed_apps")
            .doc(docId)
            .update({"apps.$packageName.iconUrl": url});
      }

      print("✅ Icons uploaded to Storage");
    } catch (e) {
      print("❌ Icon upload error: $e");
    }
  }

  /// Get single app icon (on-demand)
  Future<String?> getAppIconUrl(String packageName) async {
    try {
      final docId = GetStorage().read('currentUserId');
      if (docId == null) return null;

      final storage = FirebaseStorage.instance;
      final iconRef = storage.ref().child('app_icons/$docId/$packageName.webp');
      return await iconRef.getDownloadURL();
    } catch (_) {
      return null;
    }
  }

  Future<void> saveData(Map<String, dynamic> data) async {
    try {
      final docId = GetStorage().read('currentUserId');
      if (docId == null) throw Exception("currentUserId missing");

      await FirebaseFirestore.instance
          .collection("installed_apps")
          .doc(docId)
          .set(data, SetOptions(merge: true));
      print("✅ Installed apps saved (${data['appCount']} apps)");
    } catch (e) {
      print("❌ Firebase save error (installed apps): $e");
      rethrow;
    }
  }

  // ============ Helpers ============
  Uint8List _decodeBase64(String b64) {
    // Handle both standard and URL-safe base64
    final normalized = b64.replaceAll('-', '+').replaceAll('_', '/');
    final padding = normalized.length % 4;
    final padded = padding == 0 ? normalized : normalized + '=' * (4 - padding);
    return base64Decode(padded);
  }
}
