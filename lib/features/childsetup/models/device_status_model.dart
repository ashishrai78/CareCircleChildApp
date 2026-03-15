class DeviceStatus {
  final int batteryLevel;
  final bool isCharging;
  final bool screenOn;
  final bool deviceOnline;
  final String? appInForeground;
  final DateTime lastActive;

  DeviceStatus({
    required this.batteryLevel,
    required this.isCharging,
    required this.screenOn,
    required this.deviceOnline,
    required this.lastActive,
    this.appInForeground,
  });

  factory DeviceStatus.fromMap(Map<String, dynamic> map) {
    return DeviceStatus(
      batteryLevel: map['batteryLevel'],
      isCharging: map['isCharging'],
      screenOn: map['screenOn'],
      deviceOnline: map['deviceOnline'],
      appInForeground: map['appInForeground'],
      lastActive: DateTime.parse(map['lastActive']),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'batteryLevel': batteryLevel,
      'isCharging': isCharging,
      'screenOn': screenOn,
      'deviceOnline': deviceOnline,
      'appInForeground': appInForeground,
      'lastActive': lastActive.toIso8601String(),
    };
  }
}
