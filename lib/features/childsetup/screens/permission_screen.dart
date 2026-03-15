import 'package:background/features/childsetup/screens/child_code_display_screen.dart';
import 'package:background/utils/constants/sizes.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../../common/widgets/elevatedButton/elevated_button.dart';
import '../../../utils/constants/colors.dart';
import '../controller/permission_controller.dart';

class PermissionScreen extends StatelessWidget {
  const PermissionScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = Get.put(SetupController());

    return Scaffold(
      appBar: AppBar(
        title: Text("Child Setup", style: Theme.of(context).textTheme.headlineSmall!.apply(color: UColors.white)),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.only(bottomLeft: Radius.circular(8), bottomRight: Radius.circular(8))),
        centerTitle: true,
        backgroundColor: UColors.primary,
        elevation: 0,
      ),
      body: Padding(
        padding: const EdgeInsets.all(USizes.defaultSpace/2),
        child: SingleChildScrollView(
          child: Obx(
            ()=> Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // Header
                  const Icon(Icons.security, size: 80, color: Colors.blueAccent,),
                  const SizedBox(height: 20),
                  const Text('Almost there!',
                    style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold, color: Colors.blueAccent,),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  const Text('Grant the following permissions so we can keep your child safe.',
                    style: TextStyle(fontSize: 16, color: Colors.black54),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 40),

                  // Permission Cards
                  /// For Location
                  _buildPermissionCard(
                    icon: Icons.location_on,
                    title: 'Location',
                    description: 'Allow always‑on location access to know where your child is.',
                    status: controller.locationGranted,
                    isLoading: controller.isLoadingLocation,
                    onPressed: controller.requestLocation,
                  ),
                  /// Fore Battery
                  _buildPermissionCard(
                    icon: Icons.battery_charging_full,
                    title: 'Battery Optimizations',
                    description: 'Disable battery optimizations so the app can run in background.',
                    status: controller.batteryOptimizationsDisabled,
                    isLoading: controller.isLoadingBattery,
                    onPressed: controller.disableBatteryOptimizations,
                  ),
                  /// For Usage Acces
                  _buildPermissionCard(
                    icon: Icons.show_chart,
                    title: 'Usage Access',
                    description: 'Allow access to app usage statistics for screen time tracking.',
                    status: controller.usageAccessGranted,
                    isLoading: controller.isLoadingUsage,
                    onPressed: controller.openUsageAccessSettings,
                  ),
                  /// Fore Accessibility
                  _buildPermissionCard(
                    icon: Icons.accessibility_new,
                    title: 'Accessibility',
                    description: 'Enable for advanced monitoring features (like app blocking).',
                    status: controller.accessibilityEnabled,
                    isLoading: controller.isLoadingAccessibility,
                    onPressed: controller.openAccessibilitySettings,
                  ),

                  const SizedBox(height: 30),

                  // Complete Button
                  UElevatedButton(
                      onPressed: ()=> controller.isSetupComplete.value ? Get.offAll(()=> ChildCodeDisplayScreen()) : null,
                      child: controller.isSetupComplete.value ? Text('Continue') : Text('Allow to permission')
                    ),

                  const SizedBox(height: 20),

                  // Help link
                 /* TextButton(
                    onPressed: () => controller.launchUrl(
                        'https://example.com/setup-help'), // replace with your help page
                    child: const Text('Need help?'),
                  ),*/
                ],
              ),
          ),
          ),
      ),
    );
  }


  ////================ Widgets ========================
  Widget _buildPermissionCard({
    required IconData icon,
    required String title,
    required String description,
    required RxBool status,
    required RxBool isLoading,
    required VoidCallback onPressed,
  }) {
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.05),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Padding(
        padding: const EdgeInsets.all(10.0),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: status.value
                    ? Colors.green.withOpacity(0.1)
                    : Colors.blueAccent.withOpacity(0.1),
                shape: BoxShape.circle,
              ),
              child: Icon(
                icon,
                color: status.value ? Colors.green : Colors.blueAccent,
              ),
            ),

            const SizedBox(width: 16),

            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(title,
                        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600,),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(description, style: TextStyle(fontSize: 13, color: Colors.grey.shade600),),
                ],
              ),
            ),

            const SizedBox(width: 12),

            if (status.value)
              const Icon(Icons.check_circle, color: Colors.green, size: 30)
            else
              ElevatedButton(
                onPressed: isLoading.value ? null : onPressed,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.blueAccent,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(15),
                  ),
                 // minimumSize: const Size(80, 40),
                ),
                child: isLoading.value
                    ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
                    : const Text('Allow'),
              ),
          ],
        ),
      ),
    );
  }
////================ Widgets ========================
}