import 'package:flutter/material.dart';

import '../../../../data/services/DeviceAdminService.dart';


/// Widget to guide user through Device Admin setup
class DeviceAdminSetupCard extends StatefulWidget {
  const DeviceAdminSetupCard({Key? key}) : super(key: key);

  @override
  State<DeviceAdminSetupCard> createState() => _DeviceAdminSetupCardState();
}

class _DeviceAdminSetupCardState extends State<DeviceAdminSetupCard> {
  bool _isAdminEnabled = false;
  bool _isChecking = false;

  @override
  void initState() {
    super.initState();
    _checkAdminStatus();
  }

  Future<void> _checkAdminStatus() async {
    setState(() => _isChecking = true);
    final enabled = await DeviceAdminService.isEnabled();
    setState(() {
      _isAdminEnabled = enabled;
      _isChecking = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _isAdminEnabled ? Icons.verified_user : Icons.shield_outlined,
                  color: _isAdminEnabled ? Colors.green : Colors.orange,
                  size: 32,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Device Admin Protection',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      Text(
                        _isAdminEnabled
                            ? 'Enabled — app is protected'
                            : 'Required — tap to enable',
                        style: TextStyle(
                          fontSize: 12,
                          color: _isAdminEnabled ? Colors.green : Colors.orange,
                        ),
                      ),
                    ],
                  ),
                ),
                if (_isChecking)
                  const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  Icon(
                    _isAdminEnabled ? Icons.check_circle : Icons.warning,
                    color: _isAdminEnabled ? Colors.green : Colors.orange,
                  ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Device Admin prevents the app from being uninstalled, force-stopped, or having its data cleared. '
                  'This ensures continuous protection for your child.',
              style: TextStyle(fontSize: 12, color: Colors.grey),
            ),
            const SizedBox(height: 12),
            if (!_isAdminEnabled)
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: () async {
                    await DeviceAdminService.openEnableScreen();
                    // Wait for user to return from system settings
                    await Future.delayed(const Duration(seconds: 2));
                    _checkAdminStatus();
                  },
                  child: const Text('Enable Device Admin'),
                ),
              )
            else
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  onPressed: _checkAdminStatus,
                  child: const Text('Refresh Status'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}