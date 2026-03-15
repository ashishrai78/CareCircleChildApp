import 'dart:async';
import 'dart:ui';
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

}

@pragma('vm:entry-point')
Future<bool> onIosBackground(ServiceInstance service) async {

  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  return true;

}