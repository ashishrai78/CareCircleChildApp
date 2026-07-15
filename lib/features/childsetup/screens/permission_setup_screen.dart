import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../../data/services/oem_permission_service.dart';
import 'child_code_display_screen.dart';

/// 🛡️ Production Permission Setup Screen
///
/// Walks user through ALL required permissions in correct order:
///  1. Location (foreground)
///  2. Location (background — must be after #1)
///  3. Microphone (for WebRTC listening)
///  4. Notifications (Android 13+)
///  5. Usage Stats (screen time)
///  6. Accessibility (for Watchdog survival)
///  7. Battery Optimization
///  8. OEM AutoStart (Xiaomi/Realme/Oppo/Vivo/Samsung etc.)
class PermissionSetupScreen extends StatefulWidget {
  const PermissionSetupScreen({Key? key}) : super(key: key);

  @override
  State<PermissionSetupScreen> createState() => _PermissionSetupScreenState();
}

class _PermissionSetupScreenState extends State<PermissionSetupScreen> {
  static const _watchdogChannel = MethodChannel('watchdog_channel');
  static const _usageChannel = MethodChannel('usage_channel');

  int _currentStep = 0;
  bool _isLoading = false;
  String? _oemDisplayName;
  bool _needsAutoStart = false;

  final List<_PermissionStep> _steps = [
    _PermissionStep(
      title: 'Location Access',
      description:
          'Required to track your child\'s location. Allows parents to see where their child is in real time.',
      icon: Icons.location_on,
      color: Colors.blue,
    ),
    _PermissionStep(
      title: 'Background Location',
      description:
          'Allows location tracking even when the app is closed. This is essential for continuous monitoring.',
      icon: Icons.location_searching,
      color: Colors.blue.shade700,
    ),
    _PermissionStep(
      title: 'Microphone Access',
      description:
          'Allows parents to listen to surroundings (when explicitly enabled). This is only used when the parent requests live audio.',
      icon: Icons.mic,
      color: Colors.orange,
    ),
    _PermissionStep(
      title: 'Notifications',
      description:
          'Required on Android 13+ to keep the monitoring service alive in the background.',
      icon: Icons.notifications,
      color: Colors.purple,
    ),
    _PermissionStep(
      title: 'Usage Stats Access',
      description:
          'Required to track daily screen time and which apps your child uses. You\'ll be redirected to Settings to enable this.',
      icon: Icons.analytics,
      color: Colors.teal,
    ),
    _PermissionStep(
      title: 'Accessibility Service',
      description:
          'CRITICAL for keeping monitoring alive on aggressive OEM ROMs (Xiaomi, Oppo, Vivo, etc.). You\'ll be redirected to Settings to enable this.',
      icon: Icons.accessibility_new,
      color: Colors.red,
    ),
    _PermissionStep(
      title: 'Battery Optimization',
      description:
          'Prevents Android from killing CareCircle in battery saver mode. Tap to allow.',
      icon: Icons.battery_charging_full,
      color: Colors.green,
    ),
    _PermissionStep(
      title: 'OEM AutoStart Permission',
      description:
          'Final critical step: Your device manufacturer may still kill the app. Enable AutoStart for CareCircle in the next screen.',
      icon: Icons.phone_android,
      color: Colors.deepOrange,
    ),
  ];

  @override
  void initState() {
    super.initState();
    _loadOemInfo();
  }

  Future<void> _loadOemInfo() async {
    final name = await OemPermissionService.getOEMDisplayName();
    final needs = await OemPermissionService.needsAutoStartPermission();
    setState(() {
      _oemDisplayName = name;
      _needsAutoStart = needs;
    });
  }

  Future<void> _requestCurrentStep() async {
    setState(() => _isLoading = true);

    try {
      switch (_currentStep) {
        case 0: // Location
          await Permission.locationWhenInUse.request();
          break;
        case 1: // Background Location
          await Permission.locationAlways.request();
          break;
        case 2: // Microphone
          await Permission.microphone.request();
          break;
        case 3: // Notifications
          if (await Permission.notification.isDenied) {
            await Permission.notification.request();
          }
          break;
        case 4: // Usage Stats
          await _usageChannel.invokeMethod('openUsageStatsSettings');
          await Future.delayed(const Duration(seconds: 1));
          break;
        case 5: // Accessibility
          await _watchdogChannel.invokeMethod('openAccessibilitySettings');
          await Future.delayed(const Duration(seconds: 1));
          break;
        case 6: // Battery Optimization
          await OemPermissionService.requestIgnoreBatteryOptimization();
          break;
        case 7: // OEM AutoStart
          if (_needsAutoStart) {
            await OemPermissionService.openAutoStartSettings();
            await Future.delayed(const Duration(seconds: 1));
            await OemPermissionService.setUserConfirmedAutoStart();
          }
          break;
      }

      if (_currentStep < _steps.length - 1) {
        setState(() => _currentStep++);
      } else {
        _onAllPermissionsComplete();
      }
    } catch (e) {
      Get.snackbar(
        'Error',
        'Failed to request permission: $e',
        snackPosition: SnackPosition.BOTTOM,
      );
    } finally {
      setState(() => _isLoading = false);
    }
  }

  void _onAllPermissionsComplete() {
    GetStorage().write('permissions_setup_complete', true);
    Get.snackbar(
      '✅ All Set!',
      'CareCircle is now configured for production monitoring',
      snackPosition: SnackPosition.BOTTOM,
      duration: const Duration(seconds: 3),
    );
    Get.offAll(()=> ChildCodeDisplayScreen());
  }

  void _skipStep() {
    if (_currentStep < _steps.length - 1) {
      setState(() => _currentStep++);
    } else {
      _onAllPermissionsComplete();
    }
  }

  @override
  Widget build(BuildContext context) {
    final step = _steps[_currentStep];
    final isLastOemStep = _currentStep == 7 && !_needsAutoStart;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Setup Permissions'),
        automaticallyImplyLeading: false,
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            LinearProgressIndicator(
              value: (_currentStep + 1) / _steps.length,
              minHeight: 8,
              backgroundColor: Colors.grey.shade200,
            ),
            const SizedBox(height: 8),
            Text(
              'Step ${_currentStep + 1} of ${_steps.length}',
              style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),

            // OEM info banner
            if (_currentStep == 7 && _oemDisplayName != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.blue.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.blue.shade200),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.phone_android, color: Colors.blue),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Detected: $_oemDisplayName',
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
            ],

            Container(
              width: 80,
              height: 80,
              decoration: BoxDecoration(
                color: step.color.withOpacity(0.1),
                shape: BoxShape.circle,
              ),
              child: Icon(step.icon, size: 40, color: step.color),
            ),
            const SizedBox(height: 24),

            Text(
              step.title,
              style: const TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),

            Text(
              step.description,
              style: TextStyle(
                fontSize: 14,
                color: Colors.grey.shade700,
                height: 1.5,
              ),
            ),

            if (_currentStep == 4 ||
                _currentStep == 5 ||
                _currentStep == 7) ...[
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.orange.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.orange.shade200),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    Icon(Icons.info_outline, color: Colors.orange, size: 20),
                    SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'This will open Settings. Enable the permission for CareCircle, then come back to this app.',
                        style: TextStyle(fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ],

            const Spacer(),

            Row(
              children: [
                TextButton(
                  onPressed: _skipStep,
                  child: Text(
                    _currentStep == _steps.length - 1 ? 'Skip' : 'Skip Step',
                    style: const TextStyle(color: Colors.grey),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton(
                    onPressed: _isLoading ? null : _requestCurrentStep,
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                    child: _isLoading
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              color: Colors.white,
                              strokeWidth: 2,
                            ),
                          )
                        : Text(
                            isLastOemStep
                                ? 'Complete Setup'
                                : _currentStep == _steps.length - 1
                                    ? 'Open Settings'
                                    : 'Allow',
                          ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),

            if (_currentStep == 7 && _needsAutoStart) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: () async {
                    await OemPermissionService.setUserConfirmedAutoStart();
                    _onAllPermissionsComplete();
                  },
                  icon: const Icon(Icons.check_circle, color: Colors.green),
                  label: const Text('I\'ve Enabled AutoStart'),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    side: const BorderSide(color: Colors.green),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _PermissionStep {
  final String title;
  final String description;
  final IconData icon;
  final Color color;

  _PermissionStep({
    required this.title,
    required this.description,
    required this.icon,
    required this.color,
  });
}
