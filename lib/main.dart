import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
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
Future<void> initializeService() async {
  final service = FlutterBackgroundService();

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      autoStartOnBoot: true,
      isForegroundMode: true,
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
