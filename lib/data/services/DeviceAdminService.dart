import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 🛡️ Device Admin Service — bridges Flutter to native Device Admin
///
/// Features:
///  - Check if Device Admin is enabled
///  - Open system settings to enable Device Admin
///  - Disable Device Admin (for unpair/reset)
///  - Lock device remotely (parental control feature)
///  - Disable camera remotely
class DeviceAdminService {
  static const MethodChannel _channel = MethodChannel('device_admin_channel');

  /// Check if Device Admin is enabled
  static Future<bool> isEnabled() async {
    try {
      final result = await _channel.invokeMethod<bool>('isDeviceAdminEnabled');
      return result ?? false;
    } catch (e) {
      debugPrint('🔥 isDeviceAdminEnabled failed: $e');
      return false;
    }
  }

  /// Open system Device Admin enable screen
  /// Returns true if intent launched successfully
  static Future<bool> openEnableScreen() async {
    try {
      final result = await _channel.invokeMethod<bool>('openDeviceAdminSettings');
      return result ?? false;
    } catch (e) {
      debugPrint('🔥 openDeviceAdminSettings failed: $e');
      return false;
    }
  }

  /// Disable Device Admin (use during unpair/account deletion)
  static Future<bool> disable() async {
    try {
      final result = await _channel.invokeMethod<bool>('disableDeviceAdmin');
      return result ?? false;
    } catch (e) {
      debugPrint('🔥 disableDeviceAdmin failed: $e');
      return false;
    }
  }

  /// Lock device immediately (parental control — remote lock)
  static Future<bool> lockDeviceNow() async {
    try {
      final result = await _channel.invokeMethod<bool>('lockDeviceNow');
      return result ?? false;
    } catch (e) {
      debugPrint('🔥 lockDeviceNow failed: $e');
      return false;
    }
  }

  /// Disable/enable camera (parental control feature)
  /// [disable] - true to disable camera, false to enable
  static Future<bool> setCameraDisabled({required bool disable}) async {
    try {
      final result = await _channel.invokeMethod<bool>('disableCamera', {
        'disable': disable,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('🔥 disableCamera failed: $e');
      return false;
    }
  }
}