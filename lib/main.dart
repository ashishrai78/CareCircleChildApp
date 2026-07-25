import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:workmanager/workmanager.dart';

import 'data/repositories/authentication/authentication_repository.dart';
import 'data/services/foreground_service.dart';  // 🔥 onStart + onIosBackground import
import 'firebase_options.dart';
import 'my_app.dart';

/// 🛡️ PRODUCTION main.dart (v2 — fixed)
///
/// Fixes vs v1:
///  1. UID passed to native BEFORE watchdog start (was after)
///  2. debugPrint instead of print (was blocking in release)
///  3. Auth state listener — restarts watchdog on login
///  4. Listens to accessibility revoked events from native
///  5. Crash-safe — each init wrapped in try/catch
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
  //_accessibilityEventController = StreamController<String>.broadcast();

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

  try {
    await initializeService();
    debugPrint("✅ Foreground service initialized");
  } catch (e) {
    debugPrint("🔥 Foreground service init failed: $e");
  }

  // 🔥 Setup accessibility event listener (native → Flutter)
  _setupAccessibilityEventListener();

  // 🔥 Setup auth state listener — restarts watchdog on login
  _setupAuthStateListener();

  // 7. Native watchdog — pass UID FIRST, then start
  Future.delayed(const Duration(seconds: 2), () async {
    await _startNativeWatchdog();
    debugPrint("✅ Native watchdog started (delayed)");
  });

  // 8. RUN
  runApp(MyApp());
}

/// 🔥 Listen to accessibility revoked events from native
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

/// 🔥 Auth state listener — restarts watchdog with new UID on login
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
            debugPrint("✅ Watchdog restarted with new UID");
          } catch (e) {
            debugPrint("🔥 Native UID notify failed: $e");
          }
        }
      } catch (e) {}
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

/// Foreground Service setup
/// 🔥 onStart and onIosBackground imported from foreground_service.dart
Future<void> initializeService() async {
  final service = FlutterBackgroundService();

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,  // 🔥 From foreground_service.dart
      autoStart: true,
      autoStartOnBoot: true,
      isForegroundMode: true,
      notificationChannelId: kNotificationChannelId,
      initialNotificationTitle: 'CareCircle Protection Active',
      initialNotificationContent: 'Monitoring is running',
      foregroundServiceNotificationId: kNotificationId,
    ),
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,  // 🔥 From foreground_service.dart
      onBackground: onIosBackground,  // 🔥 From foreground_service.dart
    ),
  );

  await service.startService();
}

/// Workmanager — revives services if killed
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    try {
      debugPrint("🔄 Workmanager task: $task");

      final service = FlutterBackgroundService();
      final running = await service.isRunning();

      if (!running) {
        await service.startService();
        debugPrint("✅ Flutter service restarted by Workmanager");

        // Wait for service to actually come up
        await Future.delayed(const Duration(seconds: 3));
      }

      // Native watchdog bhi restart karo
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

/// 🔥 FIX: Pass UID FIRST, then start watchdog
Future<void> _startNativeWatchdog() async {
  try {
    const platform = MethodChannel('watchdog_channel');

    // 🔥 Pass UID FIRST
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