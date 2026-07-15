import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';

/// 🛡️ Flutter-side OEM Permission Service
///
/// Wraps the native AutoStartHelper.kt for easy use from Flutter UI.
/// Handles:
///  - OEM detection (Xiaomi, Realme, Oppo, Vivo, Samsung, etc.)
///  - AutoStart settings launch
///  - Battery optimization
///  - Persistence (user confirmed they enabled AutoStart)
class OemPermissionService {
  static const _channel = MethodChannel('watchdog_channel');

  /// Detect current OEM family
  static Future<String> getOEMFamily() async {
    try {
      return await _channel.invokeMethod<String>('getOEMFamily') ?? 'OTHER';
    } catch (_) {
      return 'OTHER';
    }
  }

  /// Get user-friendly OEM display name
  static Future<String> getOEMDisplayName() async {
    try {
      return await _channel.invokeMethod<String>('getOEMDisplayName') ??
          'Unknown Device';
    } catch (_) {
      return 'Unknown Device';
    }
  }

  /// Check if device likely needs AutoStart permission
  static Future<bool> needsAutoStartPermission() async {
    try {
      return await _channel.invokeMethod<bool>('needsAutoStartPermission') ??
          false;
    } catch (_) {
      return false;
    }
  }

  /// Open OEM-specific AutoStart settings screen
  /// Returns true if successfully opened
  static Future<bool> openAutoStartSettings() async {
    try {
      return await _channel.invokeMethod<bool>('openAutoStartSettings') ??
          false;
    } catch (e) {
      print('❌ openAutoStartSettings failed: $e');
      return false;
    }
  }

  /// Open Android's battery optimization settings
  static Future<bool> openBatteryOptimizationSettings() async {
    try {
      return await _channel.invokeMethod<bool>('openBatteryOptimizationSettings') ??
          false;
    } catch (e) {
      print('❌ openBatteryOptimizationSettings failed: $e');
      return false;
    }
  }

  /// Direct request to ignore battery optimization (shows system dialog)
  static Future<bool> requestIgnoreBatteryOptimization() async {
    try {
      return await _channel.invokeMethod<bool>(
              'requestIgnoreBatteryOptimizationDirect') ??
          false;
    } catch (e) {
      print('❌ requestIgnoreBatteryOptimization failed: $e');
      return false;
    }
  }

  /// Check if user has already confirmed AutoStart is enabled (persisted)
  static bool hasUserConfirmedAutoStart() {
    return GetStorage().read<bool>('autostart_confirmed') ?? false;
  }

  /// Mark AutoStart as confirmed by user
  static Future<void> setUserConfirmedAutoStart() async {
    await GetStorage().write('autostart_confirmed', true);
  }

  /// Reset confirmation (for re-prompt after app update)
  static Future<void> resetAutoStartConfirmation() async {
    await GetStorage().remove('autostart_confirmed');
  }

  /// Should we show the AutoStart prompt?
  /// Returns true if:
  ///  - Device needs it (OEM check)
  ///  - User hasn't confirmed yet
  static Future<bool> shouldShowAutoStartPrompt() async {
    final needs = await needsAutoStartPermission();
    final confirmed = hasUserConfirmedAutoStart();
    return needs && !confirmed;
  }
}

/// 📱 Reusable OEM Permission Dialog Widget
///
/// Shows a dialog explaining why AutoStart is needed and provides buttons to:
///  - Open AutoStart settings
///  - Open Battery Optimization
///  - Skip (with warning)
class OemPermissionDialog extends StatelessWidget {
  final bool dismissible;

  const OemPermissionDialog({
    Key? key,
    this.dismissible = false,
  }) : super(key: key);

  /// Show the dialog
  static Future<void> show(BuildContext context, {bool dismissible = false}) {
    return showDialog(
      context: context,
      barrierDismissible: dismissible,
      builder: (_) => OemPermissionDialog(dismissible: dismissible),
    );
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Map<String, dynamic>>(
      future: _loadInfo(),
      builder: (context, snapshot) {
        final oemName = snapshot.data?['oemName'] ?? 'Your device';
        final needs = snapshot.data?['needs'] ?? false;

        if (!needs) {
          return AlertDialog(
            title: const Text('✅ No Special Permission Needed'),
            content: const Text(
                'Your device uses stock Android — no AutoStart permission required.'),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('OK'),
              ),
            ],
          );
        }

        return AlertDialog(
          title: const Text('⚠️ Enable AutoStart Permission'),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  'Device: $oemName',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 12),
                const Text(
                  'Your phone manufacturer may kill CareCircle in the background to save battery. '
                  'To ensure monitoring continues even when the app is closed, please enable:',
                  style: TextStyle(fontSize: 14),
                ),
                const SizedBox(height: 12),
                _bulletPoint('AutoStart / Auto-launch for CareCircle'),
                _bulletPoint('Background running permission'),
                _bulletPoint('Battery optimization: Don\'t optimize CareCircle'),
                const SizedBox(height: 12),
                const Text(
                  'After enabling, return to the app and tap "I\'ve Enabled It".',
                  style: TextStyle(fontSize: 13, color: Colors.grey),
                ),
              ],
            ),
          ),
          actions: [
            if (dismissible)
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Later'),
              ),
            TextButton(
              onPressed: () async {
                await OemPermissionService.openBatteryOptimizationSettings();
              },
              child: const Text('Battery Opt.'),
            ),
            ElevatedButton(
              onPressed: () async {
                final success =
                    await OemPermissionService.openAutoStartSettings();
                if (!success) {
                  Get.snackbar(
                    'Note',
                    'Could not open AutoStart settings. Please enable manually in Settings.',
                    snackPosition: SnackPosition.BOTTOM,
                  );
                }
              },
              child: const Text('Open AutoStart'),
            ),
            ElevatedButton.icon(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
              onPressed: () async {
                await OemPermissionService.setUserConfirmedAutoStart();
                Navigator.pop(context);
                Get.snackbar(
                  '✅ Confirmed',
                  'AutoStart permission marked as enabled',
                  snackPosition: SnackPosition.BOTTOM,
                );
              },
              icon: const Icon(Icons.check, color: Colors.white),
              label: const Text('I\'ve Enabled It',
                  style: TextStyle(color: Colors.white)),
            ),
          ],
        );
      },
    );
  }

  Future<Map<String, dynamic>> _loadInfo() async {
    final oemName = await OemPermissionService.getOEMDisplayName();
    final needs = await OemPermissionService.needsAutoStartPermission();
    return {'oemName': oemName, 'needs': needs};
  }

  Widget _bulletPoint(String text) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('• ', style: TextStyle(fontWeight: FontWeight.bold)),
          Expanded(child: Text(text, style: const TextStyle(fontSize: 13))),
        ],
      ),
    );
  }
}
