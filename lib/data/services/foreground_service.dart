import 'dart:async';
import 'dart:ui';

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
///  - Restore WebRTC session after service restart
///  - Receive UID from main/authentication repository
///  - Setup Firestore listener only after UID is available
///
/// Architecture:
///  - Data collection handled NATIVELY
///  - WebRTC handled here in Flutter background service isolate
@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  // ---------------------------------------------------------------------------
  // FIREBASE INITIALIZATION
  // ---------------------------------------------------------------------------

  try {
    await Firebase.initializeApp();
    debugPrint('✅ Firebase initialized in foreground service');
  } catch (e) {
    debugPrint('🔥 Firebase init failed in service: $e');
  }

  // ---------------------------------------------------------------------------
  // SERVICES / STORAGE
  // ---------------------------------------------------------------------------

  final webrtc = WebRTCAudioSender();
  final storage = GetStorage();

  // ---------------------------------------------------------------------------
  // RESTORE WEBRTC STATE
  // ---------------------------------------------------------------------------

  bool webrtcRunning =
      storage.read<bool>('webrtc_running') ?? false;

  String? lastCallId =
  storage.read<String>('webrtc_call_id');

  // ---------------------------------------------------------------------------
  // ACTIVE UID
  // ---------------------------------------------------------------------------

  String? activeUserId =
  storage.read<String>('currentUserId');

  debugPrint(
    '🆔 Service startup UID: $activeUserId',
  );

  // ---------------------------------------------------------------------------
  // FOREGROUND NOTIFICATION
  // ---------------------------------------------------------------------------

  if (service is AndroidServiceInstance) {
    service.setForegroundNotificationInfo(
      title: 'CareCircle Protection Active',
      content: 'Monitoring is running',
    );
  }

  // ---------------------------------------------------------------------------
  // RESTORE PREVIOUS WEBRTC SESSION
  // ---------------------------------------------------------------------------

  if (webrtcRunning &&
      lastCallId != null &&
      lastCallId.isNotEmpty) {
    try {
      debugPrint(
        '🔄 Restoring WebRTC session: $lastCallId',
      );

      await webrtc.start(lastCallId);

      debugPrint(
        '✅ WebRTC restored after service restart '
            '(callId=$lastCallId)',
      );
    } catch (e) {
      debugPrint(
        '⚠️ WebRTC restore failed: $e',
      );

      webrtcRunning = false;

      await storage.write(
        'webrtc_running',
        false,
      );

      await storage.remove(
        'webrtc_call_id',
      );
    }
  }

  // ---------------------------------------------------------------------------
  // VARIABLES FOR FIRESTORE LISTENER
  // ---------------------------------------------------------------------------

  StreamSubscription<DocumentSnapshot<Map<String, dynamic>>>?
  controlSub;

  Timer? retryTimer;

  Timer? uidWatcherTimer;

  // ---------------------------------------------------------------------------
  // SETUP FIRESTORE CONTROL LISTENER
  // ---------------------------------------------------------------------------

  Future<void> setupControlListener(
      String? userId,
      ) async {
    final docId = userId ?? activeUserId;

    debugPrint(
      '🎯 setupControlListener() UID: $docId',
    );

    // UID not available yet.
    //
    // IMPORTANT:
    // We DO NOT use FirebaseAuth.currentUser here because
    // background service isolate may start before FirebaseAuth
    // finishes restoring the user session.
    if (docId == null || docId.isEmpty) {
      debugPrint(
        '⚠️ currentUserId missing — waiting for setUserId event',
      );

      return;
    }

    // Save UID locally as well.
    activeUserId = docId;

    await storage.write(
      'currentUserId',
      docId,
    );

    // Cancel pending retry.
    retryTimer?.cancel();
    retryTimer = null;

    // Cancel old Firestore listener.
    await controlSub?.cancel();
    controlSub = null;

    debugPrint(
      '📡 Setting up sync_mic listener for child $docId',
    );

    try {
      controlSub = FirebaseFirestore.instance
          .collection(
        WebRTCConfig.childControlCollection,
      )
          .doc(docId)
          .snapshots()
          .listen(
            (snapshot) async {
          debugPrint(
            '📥 Firestore snapshot received '
                'child_control/$docId '
                'exists=${snapshot.exists}',
          );

          if (!snapshot.exists) {
            debugPrint(
              '⚠️ Control document does not exist yet: '
                  'child_control/$docId',
            );

            return;
          }

          final data = snapshot.data();

          if (data == null) {
            debugPrint(
              '⚠️ Control document data is null',
            );

            return;
          }

          debugPrint(
            '📦 Control data: $data',
          );

          await _handleControlUpdate(
            service: service,
            data: data,
            webrtc: webrtc,
            storage: storage,
            webrtcRunning: () => webrtcRunning,
            setWebrtcRunning: (value) {
              webrtcRunning = value;
            },
          );
        },
        onError: (error) {
          debugPrint(
            '🔥 Snapshot listener error: $error',
          );

          controlSub = null;

          // Retry listener after 30 seconds.
          retryTimer?.cancel();

          retryTimer = Timer(
            const Duration(seconds: 30),
                () {
              if (activeUserId != null &&
                  activeUserId!.isNotEmpty) {
                debugPrint(
                  '🔄 Retrying Firestore control listener...',
                );

                setupControlListener(
                  activeUserId,
                );
              }
            },
          );
        },
        cancelOnError: false,
      );

      debugPrint(
        '✅ Firestore sync_mic listener attached '
            'for UID=$docId',
      );
    } catch (e) {
      debugPrint(
        '🔥 Failed to setup Firestore listener: $e',
      );

      retryTimer?.cancel();

      retryTimer = Timer(
        const Duration(seconds: 30),
            () {
          if (activeUserId != null &&
              activeUserId!.isNotEmpty) {
            setupControlListener(
              activeUserId,
            );
          }
        },
      );
    }
  }

  // ---------------------------------------------------------------------------
  // RECEIVE UID FROM MAIN / AUTHENTICATION REPOSITORY
  // ---------------------------------------------------------------------------

  service.on('setUserId').listen(
        (event) async {
      try {
        debugPrint(
          '📩 setUserId event received: $event',
        );

        final uid = event?['uid']?.toString();

        if (uid == null || uid.isEmpty) {
          debugPrint(
            '⚠️ setUserId event received but UID is empty',
          );

          return;
        }

        debugPrint(
          '🆔 New active UID received: $uid',
        );

        // If same UID is already active and listener exists,
        // no need to recreate listener.
        if (activeUserId == uid &&
            controlSub != null) {
          debugPrint(
            'ℹ️ Same UID already active. Listener is running.',
          );

          return;
        }

        activeUserId = uid;

        await storage.write(
          'currentUserId',
          uid,
        );

        debugPrint(
          '💾 UID saved to GetStorage: $uid',
        );

        await setupControlListener(
          uid,
        );
      } catch (e) {
        debugPrint(
          '🔥 Failed to handle setUserId event: $e',
        );
      }
    },
  );

  // ---------------------------------------------------------------------------
  // INITIAL FIRESTORE LISTENER
  // ---------------------------------------------------------------------------

  if (activeUserId != null &&
      activeUserId!.isNotEmpty) {
    debugPrint(
      '🚀 Initial UID available. Setting up listener...',
    );

    await setupControlListener(
      activeUserId,
    );
  } else {
    debugPrint(
      '⏳ No UID at service startup. '
          'Waiting for setUserId event...',
    );
  }

  // ---------------------------------------------------------------------------
  // WATCH UID CHANGES
  // ---------------------------------------------------------------------------

  String? lastKnownUid =
  storage.read<String>('currentUserId');

  uidWatcherTimer = Timer.periodic(
    const Duration(seconds: 10),
        (timer) async {
      try {
        final currentUid =
        storage.read<String>('currentUserId');

        if (currentUid == null ||
            currentUid.isEmpty) {
          return;
        }

        if (currentUid != lastKnownUid) {
          debugPrint(
            '🔄 UID changed in storage: '
                '$lastKnownUid → $currentUid',
          );

          lastKnownUid = currentUid;

          activeUserId = currentUid;

          await setupControlListener(
            currentUid,
          );
        }
      } catch (e) {
        debugPrint(
          '⚠️ UID watcher error: $e',
        );
      }
    },
  );

  // ---------------------------------------------------------------------------
  // STOP SERVICE COMMAND
  // ---------------------------------------------------------------------------

  service.on('stopService').listen(
        (event) async {
      debugPrint(
        '🛑 stopService command received',
      );

      try {
        retryTimer?.cancel();
        retryTimer = null;

        uidWatcherTimer?.cancel();
        uidWatcherTimer = null;

        await controlSub?.cancel();
        controlSub = null;

        if (webrtcRunning) {
          await webrtc.stop();

          webrtcRunning = false;

          await storage.write(
            'webrtc_running',
            false,
          );

          await storage.remove(
            'webrtc_call_id',
          );
        }
      } catch (e) {
        debugPrint(
          '⚠️ Error while stopping service: $e',
        );
      }

      service.stopSelf();
    },
  );

  // ---------------------------------------------------------------------------
  // SERVICE STOP EVENT
  // ---------------------------------------------------------------------------

  service.on('stop').listen(
        (event) async {
      debugPrint(
        '🛑 Service stop event',
      );

      try {
        retryTimer?.cancel();
        retryTimer = null;

        uidWatcherTimer?.cancel();
        uidWatcherTimer = null;

        await controlSub?.cancel();
        controlSub = null;

        if (webrtcRunning) {
          await webrtc.stop();

          webrtcRunning = false;

          await storage.write(
            'webrtc_running',
            false,
          );

          await storage.remove(
            'webrtc_call_id',
          );
        }
      } catch (e) {
        debugPrint(
          '⚠️ Service cleanup error: $e',
        );
      }
    },
  );
}

// ============================================================================
// HANDLE FIRESTORE CONTROL UPDATE
// ============================================================================

/// Handle child_control document update.
///
/// Expected Firestore fields:
///
/// sync_mic : bool
/// call_id  : String
///
/// When:
/// sync_mic == true
/// → Start WebRTC microphone
///
/// When:
/// sync_mic == false
/// → Stop WebRTC microphone
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

  debugPrint(
    '🎛️ Control update: '
        'sync_mic=$syncMic, '
        'call_id=$callId, '
        'webrtcRunning=${webrtcRunning()}',
  );

  // ==========================================================================
  // START MIC LISTENING
  // ==========================================================================

  if (syncMic == true && !webrtcRunning()) {
    if (callId == null || callId.isEmpty) {
      debugPrint(
        '⚠️ sync_mic=true but call_id missing — ignoring',
      );

      return;
    }

    debugPrint(
      '🎤 Starting WebRTC mic for call $callId',
    );

    try {
      await webrtc.start(
        callId,
      );

      setWebrtcRunning(true);

      await storage.write(
        'webrtc_running',
        true,
      );

      await storage.write(
        'webrtc_call_id',
        callId,
      );

      if (service is AndroidServiceInstance) {
        service.setForegroundNotificationInfo(
          title: 'CareCircle Listening Active',
          content: 'Parent is listening to surroundings',
        );
      }

      debugPrint(
        '✅ WebRTC mic started successfully',
      );
    } catch (e) {
      debugPrint(
        '⚠️ WebRTC mic start failed: $e',
      );

      setWebrtcRunning(false);

      await storage.write(
        'webrtc_running',
        false,
      );

      await storage.remove(
        'webrtc_call_id',
      );
    }

    return;
  }

  // ==========================================================================
  // STOP MIC LISTENING
  // ==========================================================================

  if (syncMic == false && webrtcRunning()) {
    debugPrint(
      '🛑 Stopping WebRTC mic',
    );

    try {
      await webrtc.stop();

      setWebrtcRunning(false);

      await storage.write(
        'webrtc_running',
        false,
      );

      await storage.remove(
        'webrtc_call_id',
      );

      if (service is AndroidServiceInstance) {
        service.setForegroundNotificationInfo(
          title: 'CareCircle Protection Active',
          content: 'Monitoring is running',
        );
      }

      debugPrint(
        '✅ WebRTC mic stopped successfully',
      );
    } catch (e) {
      debugPrint(
        '⚠️ WebRTC mic stop failed: $e',
      );
    }

    return;
  }

  // ==========================================================================
  // NO ACTION
  // ==========================================================================

  if (syncMic == true && webrtcRunning()) {
    debugPrint(
      'ℹ️ sync_mic=true but WebRTC is already running',
    );
  }

  if (syncMic == false && !webrtcRunning()) {
    debugPrint(
      'ℹ️ sync_mic=false and WebRTC is already stopped',
    );
  }
}

// ============================================================================
// IOS BACKGROUND
// ============================================================================

@pragma('vm:entry-point')
Future<bool> onIosBackground(
    ServiceInstance service,
    ) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  return true;
}