import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:cloud_firestore/cloud_firestore.dart';

import '../../../../utils/constants/colors.dart';
import '../../../../utils/constants/sizes.dart';
import '../../../../utils/popups/snackbars.dart';

/// 🫥 HideAppButton — hides CareCircle from child's app drawer
///
/// When pressed:
///  1. Shows confirmation dialog with secret code
///  2. Calls native hideApp() via MethodChannel
///  3. Writes status to Firestore (parent will see "App Hidden")
///  4. Shows success message
///  5. App disappears from launcher after 2-3 seconds
///
/// To unhide:
///  - Dial: *#*#2824#*#*
///  - Or open URL: carecircle://open
class HideAppButton extends StatefulWidget {
  const HideAppButton({super.key});

  @override
  State<HideAppButton> createState() => _HideAppButtonState();
}

class _HideAppButtonState extends State<HideAppButton> {
  static const _channel = MethodChannel('watchdog_channel');

  bool _isHiding = false;
  bool _isCurrentlyHidden = false;

  @override
  void initState() {
    super.initState();
    _checkHiddenStatus();
  }

  Future<void> _checkHiddenStatus() async {
    try {
      final isHidden = await _channel.invokeMethod<bool>('isAppHidden') ?? false;
      if (mounted) {
        setState(() => _isCurrentlyHidden = isHidden);
      }
    } catch (_) {}
  }

  Future<void> _onHidePressed() async {
    // Show confirmation dialog
    final shouldHide = await _showConfirmationDialog();
    if (shouldHide != true) return;

    setState(() => _isHiding = true);

    try {
      // Step 1: Call native hide
      final success = await _channel.invokeMethod<bool>('hideApp') ?? false;

      if (!success) {
        USnackBarHelpers.errorSnackBar(
          title: 'Failed',
          message: 'Could not hide app. Please try again.',
        );
        return;
      }

      // Step 2: Write status to Firestore (so parent knows)
      await _notifyParentAppHidden();

      // Step 3: Show success message
      if (mounted) {
        await _showSuccessDialog();
      }

      // Step 4: Update local state
      setState(() {
        _isHiding = false;
        _isCurrentlyHidden = true;
      });
    } catch (e) {
      USnackBarHelpers.errorSnackBar(
        title: 'Error',
        message: 'Failed to hide app: $e',
      );
      setState(() => _isHiding = false);
    }
  }

  Future<void> _onUnhidePressed() async {
    setState(() => _isHiding = true);

    try {
      final success = await _channel.invokeMethod<bool>('unhideApp') ?? false;

      if (success) {
        USnackBarHelpers.successSnackBar(
          title: 'App Visible',
          message: 'CareCircle is now visible in app drawer',
        );
        setState(() => _isCurrentlyHidden = false);

        // Update Firestore
        await _notifyParentAppUnhidden();
      } else {
        USnackBarHelpers.errorSnackBar(
          title: 'Failed',
          message: 'Could not unhide app',
        );
      }
    } catch (e) {
      USnackBarHelpers.errorSnackBar(
        title: 'Error',
        message: '$e',
      );
    } finally {
      setState(() => _isHiding = false);
    }
  }

  Future<bool?> _showConfirmationDialog() {
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        icon: Container(
          width: 64,
          height: 64,
          decoration: const BoxDecoration(
            color: UColors.warning,
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.visibility_off,
            color: UColors.warning,
            size: 36,
          ),
        ),
        title: const Text('Hide CareCircle?'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'After hiding, CareCircle will disappear from the app drawer. '
              'All monitoring features will continue working in background.',
              style: TextStyle(fontSize: 13),
            ),
            const SizedBox(height: USizes.md),
            Container(
              padding: const EdgeInsets.all(USizes.md),
              decoration: BoxDecoration(
                color: UColors.info,
                borderRadius: BorderRadius.circular(USizes.borderRadiusMd),
                border: Border.all(color: UColors.info.withValues(alpha: 0.3)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: const [
                      Icon(Icons.info, color: UColors.info, size: 18),
                      SizedBox(width: USizes.xs),
                      Text(
                        'To open again:',
                        style: TextStyle(
                          fontWeight: FontWeight.w700,
                          color: UColors.info,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: USizes.sm),
                  const Text(
                    'Open phone dialer and dial:',
                    style: TextStyle(fontSize: 12),
                  ),
                  const SizedBox(height: USizes.xs),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: USizes.md,
                      vertical: USizes.sm,
                    ),
                    decoration: BoxDecoration(
                      color: UColors.dark,
                      borderRadius: BorderRadius.circular(USizes.borderRadiusMd),
                    ),
                    child: const Text(
                      '*#*#2824#*#*',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                        color: UColors.textWhite,
                        letterSpacing: 2,
                      ),
                    ),
                  ),
                  const SizedBox(height: USizes.xs),
                  const Text(
                    '(spells "CARE" on dial pad)',
                    style: TextStyle(
                      fontSize: 11,
                      color: UColors.textSecondary,
                      fontStyle: FontStyle.italic,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: USizes.md),
            const Text(
              '⚠️ Write down this code before hiding. You will need it to access the app again.',
              style: TextStyle(
                fontSize: 12,
                color: UColors.warning,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Get.back(result: false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: UColors.warning,
            ),
            onPressed: () => Get.back(result: true),
            child: const Text('Hide App'),
          ),
        ],
      ),
    );
  }

  Future<void> _showSuccessDialog() async {
    await showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        icon: Container(
          width: 64,
          height: 64,
          decoration: const BoxDecoration(
            color: UColors.success,
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.check_circle,
            color: UColors.success,
            size: 36,
          ),
        ),
        title: const Text('App Hidden Successfully'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'CareCircle will disappear from app drawer in 2-3 seconds.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 13),
            ),
            const SizedBox(height: USizes.md),
            const Text(
              'To access again, dial:',
              style: TextStyle(fontSize: 12, color: UColors.textSecondary),
            ),
            const SizedBox(height: USizes.xs),
            Container(
              padding: const EdgeInsets.symmetric(
                horizontal: USizes.md,
                vertical: USizes.sm,
              ),
              decoration: BoxDecoration(
                color: UColors.dark,
                borderRadius: BorderRadius.circular(USizes.borderRadiusMd),
              ),
              child: const Text(
                '*#*#2824#*#*',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                  color: UColors.textWhite,
                  letterSpacing: 2,
                ),
              ),
            ),
          ],
        ),
        actions: [
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: () => Get.back(),
              child: const Text('Got It'),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _notifyParentAppHidden() async {
    try {
      final uid = GetStorage().read<String>('currentUserId');
      if (uid == null) return;

      await FirebaseFirestore.instance
          .collection('child_live_data')
          .doc(uid)
          .set({
        'isAppHidden': true,
        'appHiddenAt': FieldValue.serverTimestamp(),
      }, SetOptions(merge: true));
    } catch (_) {}
  }

  Future<void> _notifyParentAppUnhidden() async {
    try {
      final uid = GetStorage().read<String>('currentUserId');
      if (uid == null) return;

      await FirebaseFirestore.instance
          .collection('child_live_data')
          .doc(uid)
          .set({
        'isAppHidden': false,
        'appUnhiddenAt': FieldValue.serverTimestamp(),
      }, SetOptions(merge: true));
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    if (_isCurrentlyHidden) {
      return _buildUnhideButton();
    }
    return _buildHideButton();
  }

  Widget _buildHideButton() {
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton.icon(
        style: OutlinedButton.styleFrom(
          foregroundColor: UColors.warning,
          side: const BorderSide(color: UColors.warning),
          padding: const EdgeInsets.symmetric(vertical: USizes.md),
        ),
        onPressed: _isHiding ? null : _onHidePressed,
        icon: _isHiding
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: UColors.warning,
                ),
              )
            : const Icon(Icons.visibility_off, size: 20),
        label: Text(_isHiding ? 'Hiding...' : 'Hide App from Drawer'),
      ),
    );
  }

  Widget _buildUnhideButton() {
    return Container(
      padding: const EdgeInsets.all(USizes.md),
      decoration: BoxDecoration(
        color: UColors.success,
        borderRadius: BorderRadius.circular(USizes.cardRadiusMd),
        border: Border.all(color: UColors.success.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: const [
              Icon(Icons.visibility_off, color: UColors.success, size: 18),
              SizedBox(width: USizes.xs),
              Text(
                'App is currently hidden',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: UColors.success,
                ),
              ),
            ],
          ),
          const SizedBox(height: USizes.xs),
          const Text(
            'App is hidden from drawer. To open, dial *#*#2824#*#*',
            style: TextStyle(fontSize: 12, color: UColors.textSecondary),
          ),
          const SizedBox(height: USizes.sm),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: UColors.success,
                padding: const EdgeInsets.symmetric(vertical: USizes.sm),
              ),
              onPressed: _isHiding ? null : _onUnhidePressed,
              icon: _isHiding
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: UColors.textWhite,
                      ),
                    )
                  : const Icon(Icons.visibility, size: 18),
              label: Text(_isHiding ? 'Showing...' : 'Make App Visible'),
            ),
          ),
        ],
      ),
    );
  }
}
