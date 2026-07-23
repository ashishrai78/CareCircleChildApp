import 'package:flutter_webrtc/flutter_webrtc.dart';

/// 🌐 PRODUCTION WebRTC Configuration
///
/// Centralized ICE server config + signaling constants.
/// Used by BOTH child (sender) and parent (receiver) apps.
///
/// ✅ Production Setup:
///  - Google STUN (free, reliable for direct P2P)
///  - Metered TURN (paid, reliable for NAT traversal)
///  - Multi-protocol support (UDP + TCP + TLS)
class WebRTCConfig {
  WebRTCConfig._();

  /// ICE servers — STUN + TURN
  /// Order matters: STUN first (try direct P2P), TURN fallback (relay)
  static const List<Map<String, dynamic>> iceServers = [
    // ✅ Google STUN — free, 99.9% uptime (for direct P2P discovery)
    {'urls': 'stun:stun.l.google.com:19302'},
    {'urls': 'stun:stun1.l.google.com:19302'},
    {'urls': 'stun:stun2.l.google.com:19302'},
    {'urls': 'stun:stun3.l.google.com:19302'},

    // ✅ Metered STUN — backup
    {'urls': 'stun:stun.relay.metered.ca:80'},

    // 🔥 Metered TURN — production-grade, reliable NAT traversal
    // Supports UDP, TCP, and TLS for max compatibility
    {
      'urls': 'turn:global.relay.metered.ca:80',
      'username': 'bbcf61e1a367341798789c64',
      'credential': 'q+gj8mtw2NK43RPY',
    },
    {
      'urls': 'turn:global.relay.metered.ca:80?transport=tcp',
      'username': 'bbcf61e1a367341798789c64',
      'credential': 'q+gj8mtw2NK43RPY',
    },
    {
      'urls': 'turn:global.relay.metered.ca:443',
      'username': 'bbcf61e1a367341798789c64',
      'credential': 'q+gj8mtw2NK43RPY',
    },
    {
      'urls': 'turns:global.relay.metered.ca:443?transport=tcp',
      'username': 'bbcf61e1a367341798789c64',
      'credential': 'q+gj8mtw2NK43RPY',
    },
  ];

  /// WebRTC peer connection configuration
  static final Map<String, dynamic> configuration = {
    'iceServers': iceServers,
    'iceTransportPolicy': 'all',  // 'all' = STUN + TURN (recommended)
    'iceCandidatePoolSize': 10,
    'bundlePolicy': 'max-bundle',
    'rtcpMuxPolicy': 'require',
  };

  /// SDP constraints — audio only, no video
  static const Map<String, dynamic> offerConstraints = {
    'mandatory': {
      'OfferToReceiveAudio': true,
      'OfferToReceiveVideo': false,
    },
    'optional': [],
  };

  static const Map<String, dynamic> answerConstraints = {
    'mandatory': {
      'OfferToReceiveAudio': true,
      'OfferToReceiveVideo': false,
    },
    'optional': [],
  };

  /// Audio constraints for child's mic capture
  /// - Disables echo cancellation / noise suppression / auto gain
  /// - We want RAW ambient audio, not voice-optimized
  static const Map<String, dynamic> audioConstraints = {
    'audio': {
      'androidAudioSource': 1, // 1 = MIC (prevents VOICE_COMMUNICATION)
      'echoCancellation': false,
      'noiseSuppression': false,
      'autoGainControl': false,
      'channelCount': 1,
      'sampleRate': 48000,
      'sampleSize': 16,
    },
    'video': false,
  };

  /// Generate a unique call ID for WebRTC session
  /// Format: {childUid}_{timestamp_ms}
  static String generateCallId(String childUid) {
    return '${childUid}_${DateTime.now().millisecondsSinceEpoch}';
  }

  /// Connection timeout — if ICE not connected in 30s, fail
  static const Duration connectionTimeout = Duration(seconds: 30);

  /// Firestore collection paths
  static const String callsCollection = 'calls';
  static const String childControlCollection = 'child_control';

  /// Sub-collections under calls/{callId}
  static const String callerCandidatesSub = 'callerCandidates'; // parent → child
  static const String calleeCandidatesSub = 'calleeCandidates'; // child → parent
}

/// Connection state for UI
enum WebRTCConnectionState {
  idle,
  initializing,
  creatingOffer,
  waitingForAnswer,
  connecting,
  connected,
  failed,
  disconnected,
  closed,
}

extension WebRTCConnectionStateX on WebRTCConnectionState {
  String get label {
    switch (this) {
      case WebRTCConnectionState.idle:
        return 'Idle';
      case WebRTCConnectionState.initializing:
        return 'Initializing...';
      case WebRTCConnectionState.creatingOffer:
        return 'Creating offer...';
      case WebRTCConnectionState.waitingForAnswer:
        return 'Waiting for child...';
      case WebRTCConnectionState.connecting:
        return 'Connecting...';
      case WebRTCConnectionState.connected:
        return 'Connected';
      case WebRTCConnectionState.failed:
        return 'Connection failed';
      case WebRTCConnectionState.disconnected:
        return 'Disconnected';
      case WebRTCConnectionState.closed:
        return 'Closed';
    }
  }

  bool get isActive =>
      this == WebRTCConnectionState.connected ||
          this == WebRTCConnectionState.connecting;

  bool get isTransient =>
      this == WebRTCConnectionState.initializing ||
          this == WebRTCConnectionState.creatingOffer ||
          this == WebRTCConnectionState.waitingForAnswer ||
          this == WebRTCConnectionState.connecting;
}