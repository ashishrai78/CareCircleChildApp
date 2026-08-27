import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get_storage/get_storage.dart';

/// 📞 Call Monitoring Toggle — Hybrid approach
///
/// Child app asks for consent, then CallDetectorService runs in background
/// Parent app just reads data from Firestore (no remote control needed)
class CallMonitoringToggle extends StatefulWidget {
  const CallMonitoringToggle({super.key});

  @override
  State<CallMonitoringToggle> createState() => _CallMonitoringToggleState();
}

class _CallMonitoringToggleState extends State<CallMonitoringToggle> {
  static const _channel = MethodChannel('call_log_channel');
  bool _enabled = false;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _enabled = GetStorage().read<bool>('call_monitoring_enabled') ?? false;
  }

  Future<void> _toggle(bool value) async {
    setState(() => _isLoading = true);

    try {
      // Save to local storage
      await GetStorage().write('call_monitoring_enabled', value);

      // Notify native to start/stop service
      await _channel.invokeMethod('setCallMonitoringEnabled', {'enabled': value});

      setState(() => _enabled = value);

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(value
              ? '📞 Call monitoring enabled'
              : '📞 Call monitoring disabled'),
          duration: const Duration(seconds: 2),
        ),
      );
    } catch (e) {
      // Revert on failure
      await GetStorage().write('call_monitoring_enabled', !value);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed: $e')),
      );
    } finally {
      setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: ListTile(
        leading: Icon(
          Icons.phone_in_talk,
          color: _enabled ? Colors.green : Colors.grey,
        ),
        title: const Text(
          'Call Monitoring',
          style: TextStyle(fontWeight: FontWeight.w600),
        ),
        subtitle: Text(
          _enabled
              ? 'Logging incoming, outgoing & missed calls'
              : 'Enable to track calls',
          style: TextStyle(
            fontSize: 12,
            color: _enabled ? Colors.green : Colors.grey,
          ),
        ),
        trailing: _isLoading
            ? const SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(strokeWidth: 2),
        )
            : Switch(
          value: _enabled,
          onChanged: _toggle,
          activeColor: Colors.green,
        ),
      ),
    );
  }
}