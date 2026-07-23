import 'dart:async';
import 'dart:ui';
<<<<<<< HEAD
import 'package:background/data/services/webrtc_audio_sender.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter_background_service_android/flutter_background_service_android.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:get_storage/get_storage.dart';
import '../services/screen_time_service.dart';
import '../services/installed_apps_service.dart';
import 'location_deviceinfo_service.dart';

@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {

  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();
  await Firebase.initializeApp();

  final locationService = LocationService();
  final screenTimeService = ScreenTimeService();
  final installedAppsService = InstalledAppsService();
  final webrtc = WebRTCAudioSender();

  bool webrtcRunning = false;

  await locationService.initDeviceInfo();

  if (service is AndroidServiceInstance) {
    service.setForegroundNotificationInfo(
      title: "Child Monitoring Active",
      content: "Tracking running...",
    );
  }

  /// 🔥 Prevent overlapping timer execution
  bool isTaskRunning = false;

  Timer.periodic(const Duration(seconds: 10), (timer) async {

    if (isTaskRunning) return;
    isTaskRunning = true;

    try {

      print("🔄 Running background tasks...");

      final docId = GetStorage().read('currentUserId');
      print('CurrentUserId=============$docId');

      if (docId == null) {
        print("⚠️ User id missing");
        isTaskRunning = false;
        return;
      }

      final doc = await FirebaseFirestore.instance
          .collection("child_control")
          .doc(docId)
          .get();

      if (doc.exists) {

        final data = doc.data();

        /// -------- DATA SYNC --------
        if (data?["sync_request"] == true) {

          /// 1️⃣ Location
          try {
            final locationData = await locationService.collectData();
            await locationService.saveData(locationData);
          } catch (e) {
            print("⚠️ Location task failed: $e");
          }

          /// 2️⃣ Screen time
          try {
            final screenData = await screenTimeService.collectData();
            final dateKey = screenData["dateKey"];
            await screenTimeService.saveData(screenData, dateKey);
          } catch (e) {
            print("⚠️ Screen time task failed: $e");
          }

          /// 3️⃣ Installed apps (daily)
          if (DateTime.now().hour == 2) {
            try {
              final appsData = await installedAppsService.collectData();
              await installedAppsService.saveData(appsData);
            } catch (e) {
              print("⚠️ Installed apps task failed: $e");
            }
          }

          await FirebaseFirestore.instance
              .collection("child_control")
              .doc(docId)
              .update({
            "sync_request": false,
            "last_sync": FieldValue.serverTimestamp(),
          });

        }

        /// -------- MIC LISTENING --------

        final syncMic = data?["sync_mic"];

        if (syncMic == true && !webrtcRunning) {
          try {
            await webrtc.start(docId);
            webrtcRunning = true;
            if (service is AndroidServiceInstance) {
              service.setForegroundNotificationInfo(
                title: "CareCircle Listening Active",
                content: "Parent is listening surroundings",
              );
            }
          } catch (e) {
            print("⚠️ Mic start failed: $e");
          }
        }
        else if (syncMic == false && webrtcRunning) {

          try {
            await webrtc.stop();
            webrtcRunning = false;
            if (service is AndroidServiceInstance) {
              service.setForegroundNotificationInfo(
                title: "Child Monitoring Active",
                content: "Tracking running...",
              );
            }

            print("🛑 WebRTC mic stopped");

          } catch (e) {

            print("⚠️ Mic stop failed: $e");

          }

        }

      } else {

        print('⚠️ child_control document not found');

      }

    } catch (e) {

      print("🔥 Background error: $e");

    }

    isTaskRunning = false;

  });

=======
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:get_storage/get_storage.dart';
import '../services/webrtc_audio_sender.dart';
import '../services/webrtc_config.dart';

/// 🛡️ PRODUCTION Foreground Service (CHILD app)
///
/// Responsibilities:
///  - Listen for sync_mic commands from parent
///  - Start/stop WebRTCAudioSender based on command
///  - Restore WebRTC session after service restart (uses persisted state)
///
/// Architecture:
///  - Data collection (location/battery/usage) handled NATIVELY by
///    WatchdogService.kt → NativeDataCollector.kt → FirestoreClient.kt
///    (because MethodChannels aren't available in background isolate)
///  - WebRTC handled here in Flutter because flutter_webrtc plugin
///    works in background service isolate
@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  try {
    await Firebase.initializeApp();
  } catch (e) {
    debugPrint('🔥 Firebase init failed in service: $e');
  }

  final webrtc = WebRTCAudioSender();
  final storage = GetStorage();

  // Restore WebRTC state from previous service instance
  // (in case service was killed and restarted by WatchdogService)
  bool webrtcRunning = storage.read<bool>('webrtc_running') ?? false;
  String? lastCallId = storage.read<String>('webrtc_call_id');

  if (service is AndroidServiceInstance) {
    service.setForegroundNotificationInfo(
      title: 'CareCircle Protection Active',
      content: 'Monitoring is running',
    );
  }

  // Restore active WebRTC session if it was running before restart
  if (webrtcRunning && lastCallId != null) {
    try {
      await webrtc.start(lastCallId);
      debugPrint('🔄 WebRTC restored after service restart (callId=$lastCallId)');
    } catch (e) {
      debugPrint('⚠️ WebRTC restore failed: $e');
      webrtcRunning = false;
      storage.write('webrtc_running', false);
      storage.remove('webrtc_call_id');
    }
  }

  // Listen for service stop command
  service.on('stopService').listen((event) async {
    debugPrint('🛑 stopService command received');
    if (webrtcRunning) {
      await webrtc.stop();
      storage.write('webrtc_running', false);
      storage.remove('webrtc_call_id');
    }
    service.stopSelf();
  });

  // ============ FIRESTORE SNAPSHOT LISTENER (for sync_mic commands) ============
  StreamSubscription<DocumentSnapshot>? controlSub;

  Future<void> setupControlListener() async {
    final docId = storage.read<String>('currentUserId');
    if (docId == null) {
      debugPrint('⚠️ currentUserId missing — retrying in 30s');
      Timer(const Duration(seconds: 30), setupControlListener);
      return;
    }

    debugPrint('📡 Setting up sync_mic listener for child $docId');

    controlSub = FirebaseFirestore.instance
        .collection(WebRTCConfig.childControlCollection)
        .doc(docId)
        .snapshots()
        .listen(
      (snapshot) async {
        if (!snapshot.exists) return;

        final data = snapshot.data() as Map<String, dynamic>?;
        if (data == null) return;

        await _handleControlUpdate(
          service: service,
          data: data,
          webrtc: webrtc,
          storage: storage,
          webrtcRunning: () => webrtcRunning,
          setWebrtcRunning: (v) => webrtcRunning = v,
        );
      },
      onError: (e) {
        debugPrint('🔥 Snapshot listener error: $e');
        // Auto-restart listener after 30s
        Timer(const Duration(seconds: 30), () {
          controlSub?.cancel();
          setupControlListener();
        });
      },
    );
  }

  await setupControlListener();

  // Cleanup on service stop event
  service.on('stop').listen((event) async {
    debugPrint('🛑 Service stop event');
    await controlSub?.cancel();
    if (webrtcRunning) {
      await webrtc.stop();
      storage.write('webrtc_running', false);
      storage.remove('webrtc_call_id');
    }
  });
}

/// Handle child_control document update — start/stop WebRTC based on sync_mic flag
Future<void> _handleControlUpdate({
  required ServiceInstance service,
  required Map<String, dynamic> data,
  required WebRTCAudioSender webrtc,
  required GetStorage storage,
  required bool Function() webrtcRunning,
  required void Function(bool) setWebrtcRunning,
}) async {
  final syncMic = data['sync_mic'] as bool?;
  final callId = data['call_id'] as String?;

  // -------- START MIC LISTENING --------
  if (syncMic == true && !webrtcRunning()) {
    if (callId == null || callId.isEmpty) {
      debugPrint('⚠️ sync_mic=true but call_id missing — ignoring');
      return;
    }

    debugPrint('🎤 Starting WebRTC mic for call $callId');
    try {
      await webrtc.start(callId);
      setWebrtcRunning(true);
      storage.write('webrtc_running', true);
      storage.write('webrtc_call_id', callId);

      if (service is AndroidServiceInstance) {
        service.setForegroundNotificationInfo(
          title: 'CareCircle Listening Active',
          content: 'Parent is listening to surroundings',
        );
      }
      debugPrint('✅ WebRTC mic started');
    } catch (e) {
      debugPrint('⚠️ WebRTC mic start failed: $e');
      setWebrtcRunning(false);
      storage.write('webrtc_running', false);
    }
  }
  // -------- STOP MIC LISTENING --------
  else if (syncMic == false && webrtcRunning()) {
    debugPrint('🛑 Stopping WebRTC mic');
    try {
      await webrtc.stop();
      setWebrtcRunning(false);
      storage.write('webrtc_running', false);
      storage.remove('webrtc_call_id');

      if (service is AndroidServiceInstance) {
        service.setForegroundNotificationInfo(
          title: 'CareCircle Protection Active',
          content: 'Monitoring is running',
        );
      }
      debugPrint('✅ WebRTC mic stopped');
    } catch (e) {
      debugPrint('⚠️ WebRTC mic stop failed: $e');
    }
  }
>>>>>>> workspace
}

@pragma('vm:entry-point')
Future<bool> onIosBackground(ServiceInstance service) async {
<<<<<<< HEAD

  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  return true;

}
=======
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();
  return true;
}
>>>>>>> workspace
