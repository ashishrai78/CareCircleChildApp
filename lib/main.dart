import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:workmanager/workmanager.dart';

import 'data/repositories/authentication/authentication_repository.dart';
import 'firebase_options.dart';
import 'my_app.dart';

/// 🛡️ PRODUCTION main.dart (v3 — Lightweight, no FlutterBackgroundService)
///
/// CRITICAL CHANGES vs v2:
///  1. ❌ REMOVED flutter_background_service (Native CareCircleForegroundService handles everything)
///  2. ❌ REMOVED foreground_service.dart import (WebRTC now native)
///  3. ❌ REMOVED initializeService() call
///  4. ✅ App is now 50%+ lighter (no Flutter engine in background)
///  5. ✅ All background work done by native Kotlin services
///  6. ✅ WorkManager still used as fallback (revives native service)
///
/// ARCHITECTURE:
///  - Flutter app = UI only (login, dashboard, settings)
///  - Native CareCircleForegroundService = ALL background work
///    ├─ Location, battery, device info sync
///    ├─ Heartbeat
///    ├─ Call detection
///    ├─ Contacts sync
///    └─ WebRTC audio streaming
///  - WorkManager = fallback (revives native service)
const String kNotificationChannelId = 'carecircle_service';
const int kNotificationId = 8888;

final FlutterLocalNotificationsPlugin _notificationsPlugin =
    FlutterLocalNotificationsPlugin();

// Global accessibility event stream (listened by MyApp)
StreamController<String>? _accessibilityEventController;
Stream<String> get accessibilityEvents =>
    _accessibilityEventController?.stream ?? const Stream.empty();

Future<void> main() async {
  // 1. Binding
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);

  // Initialize accessibility event stream
  _accessibilityEventController = StreamController<String>.broadcast();

  try {
    await GetStorage.init();
    debugPrint("✅ GetStorage initialized");
  } catch (e) {
    debugPrint("🔥 GetStorage init failed: $e");
  }

  try {
    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );
    Get.put(AuthenticationRepository());
    debugPrint("✅ Firebase initialized");
  } catch (e) {
    debugPrint("🔥 Firebase init failed: $e");
  }

  try {
    await _createNotificationChannel();
    debugPrint("✅ Notification channel created");
  } catch (e) {
    debugPrint("🔥 Notification channel creation failed: $e");
  }

  try {
    await Workmanager().initialize(callbackDispatcher, isInDebugMode: false);
    await Workmanager().registerPeriodicTask(
      "watchdog",
      "watchdogTask",
      frequency: const Duration(minutes: 15),
      constraints: Constraints(
        networkType: NetworkType.notRequired,
        requiresBatteryNotLow: false,
        requiresCharging: false,
        requiresDeviceIdle: false,
        requiresStorageNotLow: false,
      ),
      existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
    );
    debugPrint("✅ Workmanager initialized");
  } catch (e) {
    debugPrint("🔥 Workmanager init failed: $e");
  }

  // 🔥 NOTE: No more initializeService() — Native CareCircleForegroundService handles all background work

  // Setup accessibility event listener (native → Flutter)
  _setupAccessibilityEventListener();

  // Setup auth state listener — restarts native watchdog on login
  _setupAuthStateListener();

  // Native watchdog — pass UID FIRST, then start
  Future.delayed(const Duration(seconds: 2), () async {
    await _startNativeWatchdog();
    debugPrint("✅ Native watchdog started (delayed)");
  });

  // RUN
  runApp(MyApp());
}

/// Listen to accessibility revoked events from native
void _setupAccessibilityEventListener() {
  try {
    const EventChannel('accessibility_events')
        .receiveBroadcastStream()
        .listen(
          (event) {
        debugPrint("⚠️ Accessibility event from native: $event");
        _accessibilityEventController?.add(event.toString());
      },
      onError: (e) {
        debugPrint("🔥 Accessibility event stream error: $e");
      },
    );
  } catch (e) {
    debugPrint("🔥 Failed to setup accessibility listener: $e");
  }
}

/// Auth state listener — restarts native watchdog with new UID on login
void _setupAuthStateListener() {
  try {
    Timer.periodic(const Duration(seconds: 5), (timer) async {
      try {
        final currentUid = GetStorage().read<String>('currentUserId');
        final lastNotifiedUid = GetStorage().read<String>('lastNotifiedUidToNative');

        if (currentUid != null && currentUid != lastNotifiedUid) {
          debugPrint("🔄 UID changed — notifying native: $currentUid");
          try {
            const platform = MethodChannel('watchdog_channel');
            await platform.invokeMethod('setUserId', {'uid': currentUid});
            await GetStorage().write('lastNotifiedUidToNative', currentUid);
            await platform.invokeMethod('startWatchdog');
            debugPrint("✅ Native watchdog restarted with new UID");
          } catch (e) {
            debugPrint("🔥 Native UID notify failed: $e");
          }
        }
      } catch (e) {
        // Silent fail — don't crash timer
      }
    });
  } catch (e) {
    debugPrint("🔥 Auth state listener setup failed: $e");
  }
}

/// Create notification channel BEFORE starting foreground service
Future<void> _createNotificationChannel() async {
  const AndroidNotificationChannel channel = AndroidNotificationChannel(
    kNotificationChannelId,
    'CareCircle Service',
    description: 'Keeps monitoring running in background',
    importance: Importance.low,
    showBadge: false,
  );

  await _notificationsPlugin
      .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>()
      ?.createNotificationChannel(channel);

  const AndroidInitializationSettings androidSettings =
      AndroidInitializationSettings('@mipmap/ic_launcher');
  const InitializationSettings settings =
      InitializationSettings(android: androidSettings);
  await _notificationsPlugin.initialize(settings: settings);
}

/// Workmanager — revives native service if killed
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    try {
      debugPrint("🔄 Workmanager task: $task");

      // Restart native CareCircleForegroundService
      try {
        const platform = MethodChannel('watchdog_channel');
        await platform.invokeMethod('startWatchdog');
      } catch (_) {}
    } catch (e) {
      debugPrint("🔥 Workmanager task failed: $e");
    }
    return true;
  });
}

/// Pass UID FIRST, then start native watchdog
Future<void> _startNativeWatchdog() async {
  try {
    const platform = MethodChannel('watchdog_channel');

    // Pass UID FIRST
    final uid = GetStorage().read<String>('currentUserId');
    if (uid != null) {
      await platform.invokeMethod('setUserId', {'uid': uid});
      debugPrint("✅ UID set before watchdog start: $uid");
      await GetStorage().write('lastNotifiedUidToNative', uid);
    } else {
      debugPrint("⚠️ No UID in storage — watchdog will start without user context");
    }

    // Now start watchdog
    await platform.invokeMethod('startWatchdog');
  } catch (e) {
    debugPrint("⚠️ Native watchdog start failed: $e");
  }
}
