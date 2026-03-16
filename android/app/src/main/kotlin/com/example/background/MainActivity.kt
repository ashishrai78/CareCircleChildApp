package com.example.background

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "watchdog_channel"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Watchdog channel (unchanged)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                if (call.method == "startWatchdog") {
                    val intent = Intent(this, WatchdogService::class.java)
                    startService(intent)
                    result.success(true)
                } else {
                    result.notImplemented()
                }
            }

    }
}