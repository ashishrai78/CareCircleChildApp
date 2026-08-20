import 'dart:async';
import 'dart:ui';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
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
///  - Restore WebRTC session after service restart
///  - 🔥 Auto-retry listener setup if UID not available yet
///
/// Architecture:
///  - Data collection (location/battery/usage) handled NATIVELY by
///    CareCircleForegroundService → NativeDataCollector → FirestoreClient
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
  Timer? retryTimer;

  Future<void> setupControlListener() async {
    final docId = GetStorage().read('currentUserId');
    print('CurrentUserId=============$docId');
    if (docId == null) {
      debugPrint('⚠️ currentUserId missing — will retry in 5s');
      // 🔥 FIX: Retry every 5s until UID becomes available
      final currentUserId = FirebaseAuth.instance.currentUser!.uid.toString();
      await storage.write('currentUserId', currentUserId);
      retryTimer?.cancel();
      retryTimer = Timer(const Duration(seconds: 5), () {
        setupControlListener();
      });
      return;
    }

    // 🔥 Cancel any existing retry timer
    retryTimer?.cancel();
    retryTimer = null;

    // 🔥 Cancel existing listener if any (UID may have changed)
    await controlSub?.cancel();

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
        retryTimer?.cancel();
        retryTimer = Timer(const Duration(seconds: 30), () {
          setupControlListener();
        });
      },
    );
  }

  // 🔥 Initial setup attempt
  await setupControlListener();

  // 🔥 Watch for UID changes — re-setup listener when user logs in/out
  // GetStorage doesn't have native change stream, so we poll every 10s
  String? lastKnownUid = storage.read<String>('currentUserId');
  Timer.periodic(const Duration(seconds: 10), (timer) {
    final currentUid = storage.read<String>('currentUserId');
    if (currentUid != lastKnownUid) {
      debugPrint('🔄 UID changed in storage: $lastKnownUid → $currentUid');
      lastKnownUid = currentUid;
      // Re-setup listener with new UID
      setupControlListener();
    }
  });

  // Cleanup on service stop event
  service.on('stop').listen((event) async {
    debugPrint('🛑 Service stop event');
    retryTimer?.cancel();
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
}

@pragma('vm:entry-point')
Future<bool> onIosBackground(ServiceInstance service) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();
  return true;
}