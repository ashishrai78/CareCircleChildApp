import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
<<<<<<< HEAD
import 'package:get/get.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:get_storage/get_storage.dart';
import 'package:workmanager/workmanager.dart';
import 'data/repositories/authentication/authentication_repository.dart';
import 'data/services/foreground_service.dart';
import 'firebase_options.dart';
import 'my_app.dart'; // We need to generate this, but for now it might fail if file missing. We will stub it or comment.

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);

  // Flutter Native SplashScreen
  //FlutterNativeSplash.preserve(widgetsBinding: widgetsBinding);

  // GetStorage initialize
  GetStorage.init();

  // Firebase initialize
  await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform).then((value) {
    Get.put(AuthenticationRepository());
  });

  startWatchdog();

  /// WorkManager for revive Service
  Workmanager().initialize(callbackDispatcher);
  Workmanager().registerPeriodicTask("watchdog", "watchdogTask", frequency: const Duration(minutes: 15));

  /// Foreground Service Initialize
  await initializeService();

  _listenToBackgroundService();
  runApp(MyApp());
}

/// Foreground service
=======
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:workmanager/workmanager.dart';

import 'data/repositories/authentication/authentication_repository.dart';
import 'data/services/foreground_service.dart';
import 'firebase_options.dart';
import 'my_app.dart';

/// 🛡️ PRODUCTION main.dart
///
/// Critical changes:
///  1. Notification channel created BEFORE service start (fixes Bad notification)
///  2. Correct init ORDER (await everything before runApp)
///  3. Crash-safe — each init wrapped in try/catch
///  4. Native channels for permissions & watchdog
const String kNotificationChannelId = 'carecircle_service';
const int kNotificationId = 8888;

final FlutterLocalNotificationsPlugin _notificationsPlugin =
    FlutterLocalNotificationsPlugin();

Future<void> main() async {
  // 1. Binding
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);

  try {
    // 2. Storage
    await GetStorage.init();
    print("✅ GetStorage initialized");
  } catch (e) {
    print("🔥 GetStorage init failed: $e");
  }

  try {
    // 3. Firebase
    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );
    Get.put(AuthenticationRepository());
    print("✅ Firebase initialized");
  } catch (e) {
    print("🔥 Firebase init failed: $e");
  }

  try {
    // 4. 🔥 CRITICAL: Create notification channel BEFORE any foreground service
    await _createNotificationChannel();
    print("✅ Notification channel created");
  } catch (e) {
    print("🔥 Notification channel creation failed: $e");
  }

  try {
    // 5. Workmanager
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
    print("✅ Workmanager initialized");
  } catch (e) {
    print("🔥 Workmanager init failed: $e");
  }

  try {
    // 6. Flutter Background Service
    await initializeService();
    print("✅ Foreground service initialized");
  } catch (e) {
    print("🔥 Foreground service init failed: $e");
  }

  // 7. Native watchdog — DELAYED by 2 sec to let Flutter service fully initialize
  // This prevents "Bad notification" crash when both services start simultaneously
  Future.delayed(const Duration(seconds: 2), () async {
    await _startNativeWatchdog();
    print("✅ Native watchdog started (delayed)");
  });

  // 8. RUN
  runApp(MyApp());
}

/// 🔥 CRITICAL FIX: Create notification channel BEFORE starting foreground service
///
/// On Android 8.0+ (API 26+), foreground services REQUIRE a notification channel
/// to exist before startForeground() is called. Otherwise throws:
///   "Bad notification for startForeground"
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

  // Initialize plugin with empty default icon
  const AndroidInitializationSettings androidSettings =
      AndroidInitializationSettings('@mipmap/ic_launcher');
  const InitializationSettings settings =
      InitializationSettings(android: androidSettings);
  await _notificationsPlugin.initialize(settings: settings);
}

/// Foreground Service setup
>>>>>>> workspace
Future<void> initializeService() async {
  final service = FlutterBackgroundService();

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      autoStartOnBoot: true,
      isForegroundMode: true,
<<<<<<< HEAD
    ),
    iosConfiguration: IosConfiguration(),
  );

  service.startService();
}

/// WorkManager for revive Service
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    final service = FlutterBackgroundService();
    bool running = await service.isRunning();

    if (!running) {
      service.startService();
    }

    return Future.value(true);
  });
}

/// watch dog
const platform = MethodChannel('watchdog_channel');
Future<void> startWatchdog() async {
  try {
    await platform.invokeMethod('startWatchdog');
  } catch (e) {
    print("Watchdog error: $e");
  }
}

// ✅ Naya function — file mein kahin bhi add karo
void _listenToBackgroundService() {
  final service = FlutterBackgroundService();

  service.on('disableSCO').listen((_) async {
    const channel = MethodChannel("audio_control");
    try {
      await platform.invokeMethod('disableSCO');
      print("✅ SCO disabled via bridge");
    } catch (e) {
      print("⚠️ disableSCO failed: $e");
    }
  });

  service.on('restoreAudio').listen((_) async {
    const channel = MethodChannel("audio_control");
    try {
      await platform.invokeMethod('restoreAudio');
      print("✅ Audio restored via bridge");
    } catch (e) {
      print("⚠️ restoreAudio failed: $e");
    }
  });
}
=======
      notificationChannelId: kNotificationChannelId,
      initialNotificationTitle: 'CareCircle Protection Active',
      initialNotificationContent: 'Monitoring is running',
      foregroundServiceNotificationId: kNotificationId,
    ),
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onIosBackground,
    ),
  );

  await service.startService();
}

/// Workmanager — revives services if killed
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    try {
      print("🔄 Workmanager task: $task");

      final service = FlutterBackgroundService();
      final running = await service.isRunning();
      if (!running) {
        await service.startService();
        print("✅ Flutter service restarted by Workmanager");
      }

      try {
        const platform = MethodChannel('watchdog_channel');
        await platform.invokeMethod('startWatchdog');
      } catch (_) {}
    } catch (e) {
      print("🔥 Workmanager task failed: $e");
    }

    return true;
  });
}

/// Start native watchdog service
Future<void> _startNativeWatchdog() async {
  try {
    const platform = MethodChannel('watchdog_channel');
    await platform.invokeMethod('startWatchdog');

    // 🔥 Pass current user ID to native (if logged in)
    final uid = GetStorage().read<String>('currentUserId');
    if (uid != null) {
      await platform.invokeMethod('setUserId', {'uid': uid});
      print("✅ UID passed to native: $uid");
    }
  } catch (e) {
    print("⚠️ Native watchdog start failed: $e");
  }
}
>>>>>>> workspace
