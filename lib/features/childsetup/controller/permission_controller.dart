import 'package:android_intent_plus/android_intent.dart';
import 'package:get/get.dart';
import 'package:permission_handler/permission_handler.dart';

class SetupController extends GetxController {
  // Observable permission states
  final locationGranted = false.obs;
  final batteryOptimizationsDisabled = false.obs;
  final usageAccessGranted = false.obs;
  final accessibilityEnabled = false.obs; // optional, can't directly detect

  // Loading states for each action
  final isLoadingLocation = false.obs;
  final isLoadingBattery = false.obs;
  final isLoadingUsage = false.obs;
  final isLoadingAccessibility = false.obs;

  // Overall setup completion
  final isSetupComplete = false.obs;

  @override
  void onInit() {
    super.onInit();
    checkAllPermissions();
  }

  /// Check all permissions status
  Future<void> checkAllPermissions() async {
    locationGranted.value = await Permission.locationAlways.isGranted;
    batteryOptimizationsDisabled.value = await Permission.ignoreBatteryOptimizations.isGranted;
    usageAccessGranted.value = await Permission.sensors.isGranted; // Dummy – we'll handle usage access via intent
    // We can't directly check accessibility, so we assume false
    accessibilityEnabled.value = false;
    _updateSetupComplete();
  }

  void _updateSetupComplete() {
    isSetupComplete.value = locationGranted.value && batteryOptimizationsDisabled.value && usageAccessGranted.value;
    // Accessibility is optional, not required for completion
  }

  /// Request location permission (always)
  Future<void> requestLocation() async {
    isLoadingLocation.value = true;
    try {
      await Permission.location.request();
      final status = await Permission.locationAlways.request();
      locationGranted.value = status.isGranted;
    } catch (e) {
      Get.snackbar('Error', 'Could not request location permission: $e');
    } finally {
      isLoadingLocation.value = false;
      _updateSetupComplete();
    }
  }

  /// Open battery optimization settings
  Future<void> disableBatteryOptimizations() async {
    isLoadingBattery.value = true;
    try {
      final status = await Permission.ignoreBatteryOptimizations.request();
      if (!status.isGranted) {
        // If still not granted, open system settings
        await openAppSettings();
      }
      batteryOptimizationsDisabled.value = await Permission.ignoreBatteryOptimizations.isGranted;
    } catch (e) {
      Get.snackbar('Error', 'Could not disable battery optimizations: $e');
    } finally {
      isLoadingBattery.value = false;
      _updateSetupComplete();
    }
  }

  /// Open usage access settings
  Future<void> openUsageAccessSettings() async {
    isLoadingUsage.value = true;
    try {
      const intent = AndroidIntent(
        action: 'android.settings.USAGE_ACCESS_SETTINGS',
      );
      await intent.launch();
      // After returning, we can't automatically know if granted.
      // Show a dialog asking user to confirm.
      usageAccessGranted.value = true; // optimistic – user must confirm manually
    } catch (e) {
      Get.snackbar('Error', 'Could not open usage access settings: $e');
    } finally {
      isLoadingUsage.value = false;
      _updateSetupComplete();
    }
  }

  /// Open accessibility settings
  Future<void> openAccessibilitySettings() async {
    isLoadingAccessibility.value = true;
    try {
      const intent = AndroidIntent(
        action: 'android.settings.ACCESSIBILITY_SETTINGS',
      );
      await intent.launch();
      accessibilityEnabled.value = true; // optimistic
    } catch (e) {
      Get.snackbar('Error', 'Could not open accessibility settings: $e');
    } finally {
      isLoadingAccessibility.value = false;
    }
  }

  /// Open general app settings (for auto‑start etc.)
  Future<void> openAppSettingsPage() async {
    await openAppSettings();
  }


  //// Mic permission
  Future<bool> requestMicPermission() async {

    final status = await Permission.microphone.request();

    if (status.isGranted) {
      return true;
    }

    return false;
  }

  /// Helper to launch URL (for manual steps)
  /*Future<void> launchUrl(String url) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
    } else {
      Get.snackbar('Error', 'Could not launch $url');
    }
  }*/
}