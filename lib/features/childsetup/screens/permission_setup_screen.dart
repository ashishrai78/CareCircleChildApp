import 'package:background/features/childsetup/screens/child_code_display_screen.dart';
import 'package:background/features/childsetup/screens/widgets/call_monitoring_toggle.dart';
import 'package:background/features/childsetup/screens/widgets/device_admin_setup_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:permission_handler/permission_handler.dart';

import '../../../data/services/oem_permission_service.dart';

class PermissionSetupScreen extends StatefulWidget {
  const PermissionSetupScreen({Key? key}) : super(key: key);

  @override
  State<PermissionSetupScreen> createState() =>
      _PermissionSetupScreenState();
}

class _PermissionSetupScreenState extends State<PermissionSetupScreen>
    with SingleTickerProviderStateMixin {
  static const _watchdogChannel = MethodChannel('watchdog_channel');
  static const _usageChannel = MethodChannel('usage_channel');
  static const _permissionChannel = MethodChannel('permissions_channel');

  int _currentStep = 0;
  bool _isLoading = false;

  String? _oemDisplayName;
  bool _needsAutoStart = false;

  late AnimationController _animationController;
  late Animation<double> _fadeAnimation;

  final List<_PermissionStep> _steps = const [
    _PermissionStep(
      title: 'Location Access',
      shortTitle: 'Location',
      description:
      'Allow CareCircle to access the child\'s location. This helps parents see the device location in real time.',
      icon: Icons.location_on_rounded,
      color: Color(0xFF2563EB),
      type: _PermissionType.normal,
    ),
    _PermissionStep(
      title: 'Background Location',
      shortTitle: 'Background',
      description:
      'Allow location access while the app is running in the background so tracking can continue even when CareCircle is closed.',
      icon: Icons.location_searching_rounded,
      color: Color(0xFF4F46E5),
      type: _PermissionType.normal,
    ),
    _PermissionStep(
      title: 'Microphone Access',
      shortTitle: 'Microphone',
      description:
      'Microphone access is required for live audio listening when the parent explicitly requests it.',
      icon: Icons.mic_rounded,
      color: Color(0xFFF97316),
      type: _PermissionType.normal,
    ),
    _PermissionStep(
      title: 'Contacts & Call Monitoring',
      shortTitle: 'Calls',
      description:
      'Allow the required phone permissions and choose whether call monitoring should be enabled on this device.',
      icon: Icons.phone_in_talk_rounded,
      color: Color(0xFFEC4899),
      type: _PermissionType.callMonitoring,
    ),
    _PermissionStep(
      title: 'Notifications',
      shortTitle: 'Notifications',
      description:
      'Notifications help CareCircle keep its monitoring service running reliably in the background.',
      icon: Icons.notifications_active_rounded,
      color: Color(0xFF8B5CF6),
      type: _PermissionType.settings,
    ),
    _PermissionStep(
      title: 'Usage Access',
      shortTitle: 'Screen Time',
      description:
      'Allow Usage Access so CareCircle can calculate screen time and monitor which applications are being used.',
      icon: Icons.analytics_rounded,
      color: Color(0xFF14B8A6),
      type: _PermissionType.settings,
    ),
    _PermissionStep(
      title: 'Accessibility Service',
      shortTitle: 'Accessibility',
      description:
      'Accessibility Service helps CareCircle maintain monitoring functionality on devices with aggressive background restrictions.',
      icon: Icons.accessibility_new_rounded,
      color: Color(0xFFEF4444),
      type: _PermissionType.settings,
    ),
    _PermissionStep(
      title: 'Battery Optimization',
      shortTitle: 'Battery',
      description:
      'Disable battery optimization for CareCircle so Android is less likely to stop background monitoring.',
      icon: Icons.battery_charging_full_rounded,
      color: Color(0xFF16A34A),
      type: _PermissionType.battery,
    ),
    _PermissionStep(
      title: 'Device Admin',
      shortTitle: 'Protection',
      description:
      'Enable Device Admin protection to help protect the monitoring app from being easily stopped or removed.',
      icon: Icons.admin_panel_settings_rounded,
      color: Color(0xFF0F766E),
      type: _PermissionType.deviceAdmin,
    ),
    _PermissionStep(
      title: 'AutoStart',
      shortTitle: 'AutoStart',
      description:
      'Some manufacturers can stop background apps automatically. Enable AutoStart for CareCircle if your device requires it.',
      icon: Icons.rocket_launch_rounded,
      color: Color(0xFFEA580C),
      type: _PermissionType.autoStart,
    ),
  ];

  @override
  void initState() {
    super.initState();

    _animationController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );

    _fadeAnimation = CurvedAnimation(
      parent: _animationController,
      curve: Curves.easeInOut,
    );

    _animationController.forward();

    _loadOemInfo();
  }

  @override
  void dispose() {
    _animationController.dispose();
    super.dispose();
  }

  Future<void> _loadOemInfo() async {
    try {
      final name = await OemPermissionService.getOEMDisplayName();
      final needs = await OemPermissionService.needsAutoStartPermission();

      if (!mounted) return;

      setState(() {
        _oemDisplayName = name;
        _needsAutoStart = needs;
      });
    } catch (_) {}
  }

  Future<void> _animateToNextStep() async {
    await _animationController.reverse();

    if (!mounted) return;

    setState(() {
      _currentStep++;
    });

    _animationController.forward();
  }

  Future<void> _requestCurrentStep() async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      switch (_currentStep) {
      // ---------------------------------------------------------
      // 0. FOREGROUND LOCATION
      // ---------------------------------------------------------
        case 0:
          await Permission.locationWhenInUse.request();
          break;

      // ---------------------------------------------------------
      // 1. BACKGROUND LOCATION
      // ---------------------------------------------------------
        case 1:
          await Permission.locationAlways.request();
          break;

      // ---------------------------------------------------------
      // 2. MICROPHONE
      // ---------------------------------------------------------
        case 2:
          await Permission.microphone.request();
          break;

      // ---------------------------------------------------------
      // 3. CONTACTS + PHONE
      // ---------------------------------------------------------
        case 3:
          await Permission.contacts.request();
          await Permission.phone.request();
          break;

      // ---------------------------------------------------------
      // 4. NOTIFICATIONS + NOTIFICATION ACCESS
      // ---------------------------------------------------------
        case 4:
          if (await Permission.notification.isDenied) {
            await Permission.notification.request();
          }

          final bool notificationAccessEnabled =
              await _permissionChannel.invokeMethod<bool>(
                'isNotificationAccessEnabled',
              ) ??
                  false;

          if (!notificationAccessEnabled) {
            await _permissionChannel.invokeMethod(
              'openNotificationAccessSettings',
            );

            await Future.delayed(
              const Duration(seconds: 1),
            );
          }
          break;

      // ---------------------------------------------------------
      // 5. USAGE ACCESS
      // ---------------------------------------------------------
        case 5:
          await _usageChannel.invokeMethod(
            'openUsageStatsSettings',
          );

          await Future.delayed(
            const Duration(seconds: 1),
          );
          break;

      // ---------------------------------------------------------
      // 6. ACCESSIBILITY
      // ---------------------------------------------------------
        case 6:
          await _watchdogChannel.invokeMethod(
            'openAccessibilitySettings',
          );

          await Future.delayed(
            const Duration(seconds: 1),
          );
          break;

      // ---------------------------------------------------------
      // 7. BATTERY
      // ---------------------------------------------------------
        case 7:
          await OemPermissionService.requestIgnoreBatteryOptimization();
          break;

      // ---------------------------------------------------------
      // 8. DEVICE ADMIN
      // ---------------------------------------------------------
        case 8:
        // Device Admin is displayed inside the current step.
        // User enables it from the card.
          break;

      // ---------------------------------------------------------
      // 9. OEM AUTOSTART
      // ---------------------------------------------------------
        case 9:
          if (_needsAutoStart) {
            await OemPermissionService.openAutoStartSettings();

            await Future.delayed(
              const Duration(seconds: 1),
            );

            await OemPermissionService.setUserConfirmedAutoStart();
          }
          break;
      }

      if (_currentStep < _steps.length - 1) {
        await _animateToNextStep();
      } else {
        await _onAllPermissionsComplete();
      }
    } catch (e) {
      Get.snackbar(
        'Permission Error',
        'Failed to process this permission.\n$e',
        snackPosition: SnackPosition.BOTTOM,
        margin: const EdgeInsets.all(16),
        borderRadius: 14,
        backgroundColor: Colors.red.shade50,
        colorText: Colors.red.shade900,
        duration: const Duration(seconds: 3),
      );
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _onAllPermissionsComplete() async {
    await GetStorage().write(
      'permissions_setup_complete',
      true,
    );

    if (!mounted) return;

    await showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) {
        return AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(24),
          ),
          contentPadding: const EdgeInsets.fromLTRB(
            24,
            28,
            24,
            20,
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 76,
                height: 76,
                decoration: BoxDecoration(
                  color: Colors.green.shade50,
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  Icons.check_rounded,
                  size: 44,
                  color: Colors.green.shade600,
                ),
              ),
              const SizedBox(height: 20),
              const Text(
                'Setup Complete!',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                'CareCircle is ready for monitoring.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 14,
                  height: 1.5,
                  color: Colors.grey.shade600,
                ),
              ),
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: () {
                    Get.to(()=> ChildCodeDisplayScreen());
                  },
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(
                      vertical: 14,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  child: const Text(
                    'Continue',
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  void _skipStep() {
    if (_isLoading) return;

    if (_currentStep < _steps.length - 1) {
      _animateToNextStep();
    } else {
      _onAllPermissionsComplete();
    }
  }

  void _goBack() {
    if (_isLoading) return;

    if (_currentStep > 0) {
      _animationController.reverse().then((_) {
        if (!mounted) return;

        setState(() {
          _currentStep--;
        });

        _animationController.forward();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final step = _steps[_currentStep];

    final progress = (_currentStep + 1) / _steps.length;

    return Scaffold(
      backgroundColor: const Color(0xFFF7F8FC),
      appBar: AppBar(
        elevation: 0,
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.white,
        automaticallyImplyLeading: false,
        titleSpacing: 20,
        title: Row(
          children: [
            Container(
              width: 38,
              height: 38,
              decoration: BoxDecoration(
                color: step.color.withOpacity(.10),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(
                Icons.security_rounded,
                color: step.color,
                size: 22,
              ),
            ),
            const SizedBox(width: 12),
            const Expanded(
              child: Text(
                'Device Setup',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                  color: Color(0xFF111827),
                ),
              ),
            ),
            Text(
              '${_currentStep + 1}/${_steps.length}',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: Colors.grey.shade600,
              ),
            ),
          ],
        ),
      ),
      body: SafeArea(
        child: FadeTransition(
          opacity: _fadeAnimation,
          child: Column(
            children: [
              _buildProgressHeader(progress, step),

              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(
                    20,
                    20,
                    20,
                    20,
                  ),
                  child: _buildStepContent(step),
                ),
              ),

              _buildBottomButtons(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildProgressHeader(
      double progress,
      _PermissionStep step,
      ) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(
        20,
        12,
        20,
        18,
      ),
      decoration: const BoxDecoration(
        color: Colors.white,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Setup your child\'s device',
                  style: TextStyle(
                    fontSize: 13,
                    color: Colors.grey.shade600,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Text(
                '${(progress * 100).round()}%',
                style: TextStyle(
                  fontSize: 13,
                  color: step.color,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ],
          ),
          const SizedBox(height: 9),
          ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: LinearProgressIndicator(
              value: progress,
              minHeight: 7,
              backgroundColor: Colors.grey.shade200,
              valueColor: AlwaysStoppedAnimation<Color>(
                step.color,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStepContent(_PermissionStep step) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 6),

        // ---------------------------------------------------------
        // STEP ICON
        // ---------------------------------------------------------
        Center(
          child: Container(
            width: 92,
            height: 92,
            decoration: BoxDecoration(
              color: step.color.withOpacity(.10),
              shape: BoxShape.circle,
            ),
            child: Container(
              margin: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: step.color.withOpacity(.14),
                shape: BoxShape.circle,
              ),
              child: Icon(
                step.icon,
                size: 40,
                color: step.color,
              ),
            ),
          ),
        ),

        const SizedBox(height: 22),

        // ---------------------------------------------------------
        // TITLE
        // ---------------------------------------------------------
        Text(
          step.title,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 25,
            fontWeight: FontWeight.w800,
            letterSpacing: -.4,
          ),
        ),

        const SizedBox(height: 10),

        // ---------------------------------------------------------
        // DESCRIPTION
        // ---------------------------------------------------------
        Text(
          step.description,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 14,
            height: 1.55,
            color: Colors.grey.shade600,
          ),
        ),

        const SizedBox(height: 24),

        // ---------------------------------------------------------
        // OEM INFO
        // ---------------------------------------------------------
        if (_currentStep == 9 && _oemDisplayName != null)
          _buildOemInfoCard(),

        if (_currentStep == 9 && _oemDisplayName != null)
          const SizedBox(height: 14),

        // ---------------------------------------------------------
        // SPECIAL CONTENT
        // ---------------------------------------------------------
        if (step.type == _PermissionType.callMonitoring)
          _buildCallMonitoringSection(),

        if (step.type == _PermissionType.deviceAdmin)
          _buildDeviceAdminSection(),

        if (step.type == _PermissionType.settings)
          _buildSettingsInfo(),

        if (step.type == _PermissionType.battery)
          _buildBatteryInfo(),

        if (step.type == _PermissionType.autoStart)
          _buildAutoStartInfo(),

        const SizedBox(height: 10),
      ],
    );
  }

  Widget _buildCallMonitoringSection() {
    return Column(
      children: [
        _buildInfoCard(
          icon: Icons.phone_in_talk_rounded,
          title: 'Call permissions',
          description:
          'Contacts and phone permissions are requested before continuing.',
          color: Colors.pink,
        ),

        const SizedBox(height: 14),

        const CallMonitoringToggle(),
      ],
    );
  }

  Widget _buildDeviceAdminSection() {
    return Column(
      children: [
        _buildInfoCard(
          icon: Icons.shield_rounded,
          title: 'Extra protection',
          description:
          'Device Admin provides an additional layer of protection for the child device.',
          color: Colors.teal,
        ),

        const SizedBox(height: 14),

        const DeviceAdminSetupCard(),
      ],
    );
  }

  Widget _buildSettingsInfo() {
    return _buildInfoCard(
      icon: Icons.settings_rounded,
      title: 'Android Settings',
      description:
      'The Android settings screen will open. Enable CareCircle permission and return to this app.',
      color: Colors.deepPurple,
      showArrow: true,
    );
  }

  Widget _buildBatteryInfo() {
    return _buildInfoCard(
      icon: Icons.battery_saver_rounded,
      title: 'Keep monitoring active',
      description:
      'Battery optimization can stop background services. Please allow CareCircle to run without battery restrictions.',
      color: Colors.green,
    );
  }

  Widget _buildAutoStartInfo() {
    if (!_needsAutoStart) {
      return _buildInfoCard(
        icon: Icons.check_circle_rounded,
        title: 'AutoStart not required',
        description:
        'Your device does not appear to require a separate AutoStart permission.',
        color: Colors.green,
      );
    }

    return Column(
      children: [
        _buildInfoCard(
          icon: Icons.rocket_launch_rounded,
          title: 'Detected device',
          description:
          'Your device manufacturer may restrict background applications. Enable AutoStart to improve reliability.',
          color: Colors.deepOrange,
        ),
        const SizedBox(height: 12),
        if (_oemDisplayName != null)
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(
                color: Colors.grey.shade200,
              ),
            ),
            child: Row(
              children: [
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: Colors.deepOrange.withOpacity(.10),
                    borderRadius: BorderRadius.circular(13),
                  ),
                  child: const Icon(
                    Icons.phone_android_rounded,
                    color: Colors.deepOrange,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment:
                    CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Manufacturer',
                        style: TextStyle(
                          fontSize: 11,
                          color: Colors.grey.shade500,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        _oemDisplayName!,
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _buildOemInfoCard() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.blue.shade50,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: Colors.blue.shade100,
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 42,
            height: 42,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(12),
            ),
            child: const Icon(
              Icons.phone_android_rounded,
              color: Colors.blue,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Device detected',
                  style: TextStyle(
                    fontSize: 11,
                    color: Colors.blue.shade700,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  _oemDisplayName!,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoCard({
    required IconData icon,
    required String title,
    required String description,
    required Color color,
    bool showArrow = false,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: Colors.grey.shade200,
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(.025),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: color.withOpacity(.10),
              borderRadius: BorderRadius.circular(13),
            ),
            child: Icon(
              icon,
              color: color,
              size: 22,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        title,
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                    if (showArrow)
                      Icon(
                        Icons.open_in_new_rounded,
                        size: 17,
                        color: Colors.grey.shade500,
                      ),
                  ],
                ),
                const SizedBox(height: 5),
                Text(
                  description,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.45,
                    color: Colors.grey.shade600,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBottomButtons() {
    final isLast = _currentStep == _steps.length - 1;

    final isAutoStartNotRequired =
        _currentStep == 9 && !_needsAutoStart;

    String buttonText;

    if (isLast) {
      buttonText = isAutoStartNotRequired
          ? 'Complete Setup'
          : 'Open AutoStart Settings';
    } else if (_currentStep == 8) {
      buttonText = 'Check Device Admin';
    } else if (_currentStep == 4 ||
        _currentStep == 5 ||
        _currentStep == 6) {
      buttonText = 'Open Settings';
    } else {
      buttonText = 'Allow & Continue';
    }

    return Container(
      padding: const EdgeInsets.fromLTRB(
        20,
        12,
        20,
        18,
      ),
      decoration: BoxDecoration(
        color: Colors.white,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(.06),
            blurRadius: 18,
            offset: const Offset(0, -5),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Column(
          children: [
            Row(
              children: [
                if (_currentStep > 0)
                  SizedBox(
                    width: 48,
                    height: 48,
                    child: OutlinedButton(
                      onPressed: _isLoading ? null : _goBack,
                      style: OutlinedButton.styleFrom(
                        padding: EdgeInsets.zero,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                        side: BorderSide(
                          color: Colors.grey.shade300,
                        ),
                      ),
                      child: const Icon(
                        Icons.arrow_back_rounded,
                      ),
                    ),
                  ),

                if (_currentStep > 0)
                  const SizedBox(width: 10),

                Expanded(
                  child: SizedBox(
                    height: 48,
                    child: ElevatedButton(
                      onPressed:
                      _isLoading ? null : _requestCurrentStep,
                      style: ElevatedButton.styleFrom(
                        elevation: 0,
                        backgroundColor:
                        _steps[_currentStep].color,
                        foregroundColor: Colors.white,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                      ),
                      child: _isLoading
                          ? const SizedBox(
                        width: 22,
                        height: 22,
                        child:
                        CircularProgressIndicator(
                          strokeWidth: 2.5,
                          color: Colors.white,
                        ),
                      )
                          : Row(
                        mainAxisAlignment:
                        MainAxisAlignment.center,
                        children: [
                          Text(
                            buttonText,
                            style: const TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Icon(
                            isLast
                                ? Icons.check_rounded
                                : Icons
                                .arrow_forward_rounded,
                            size: 19,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),

            const SizedBox(height: 8),

            TextButton(
              onPressed: _isLoading ? null : _skipStep,
              style: TextButton.styleFrom(
                foregroundColor: Colors.grey.shade600,
              ),
              child: Text(
                isLast ? 'Skip & Finish' : 'Skip this step',
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ================================================================
// PERMISSION STEP MODEL
// ================================================================

enum _PermissionType {
  normal,
  callMonitoring,
  settings,
  battery,
  deviceAdmin,
  autoStart,
}

class _PermissionStep {
  final String title;
  final String shortTitle;
  final String description;
  final IconData icon;
  final Color color;
  final _PermissionType type;

  const _PermissionStep({
    required this.title,
    required this.shortTitle,
    required this.description,
    required this.icon,
    required this.color,
    required this.type,
  });
}