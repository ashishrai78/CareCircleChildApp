import 'dart:async';
import 'dart:io' show Platform;
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:cloud_firestore/cloud_firestore.dart';

class WebRTCAudioSender {
  RTCPeerConnection? peerConnection;
  MediaStream? localStream;
  final FirebaseFirestore firestore = FirebaseFirestore.instance;

  bool _answerSent = false;
  bool _running = false;
  List<RTCIceCandidate> _pendingCandidates = [];

  StreamSubscription? _offerListener;
  StreamSubscription? _candidateListener;

  Future<void> start(String callId,) async {
    if (_running) {
      print("⚠️ WebRTC already running");
      return;
    }
    _running = true;
    _pendingCandidates.clear();

    final config = {
      'iceServers': [
        {'urls': 'stun:stun.l.google.com:19302'},
        {'urls': 'stun:stun1.l.google.com:19302'},
        {'urls': 'stun:stun2.l.google.com:19302'},
        {
          'urls': 'turn:openrelay.metered.ca:80',
          'username': 'openrelayproject',
          'credential': 'openrelayproject'
        },
        {
          'urls': 'turn:openrelay.metered.ca:443',
          'username': 'openrelayproject',
          'credential': 'openrelayproject'
        },
        {
          'urls': 'turns:openrelay.metered.ca:443',
          'username': 'openrelayproject',
          'credential': 'openrelayproject'
        },
        {
          'urls': 'turn:turn.anyfirewall.com:3478',
          'username': 'webrtc',
          'credential': 'webrtc'
        }
      ]
    };

    peerConnection = await createPeerConnection(config);

    // Monitor ICE connection state
    peerConnection!.onIceConnectionState = (state) {
      print("🧪 Child ICE state: $state");
    };

    peerConnection!.onIceGatheringState = (state) {
      print("🧪 Child ICE gathering: $state");
    };

    // Get microphone stream
    try {
      localStream = await navigator.mediaDevices.getUserMedia({
        'audio': {
          'echoCancellation': true,
          'noiseSuppression': true,
          'autoGainControl': true,
        },
        'video': false
      });
      print("✅ Child mic stream obtained");
    } catch (e) {
      print("❌ Child mic permission error: $e");
      _running = false;
      return;
    }

    for (var track in localStream!.getTracks()) {
      peerConnection!.addTrack(track, localStream!);
    }

    // Send local ICE candidates to Firestore
    peerConnection!.onIceCandidate = (candidate) {
      print("📤 Child sending ICE candidate");
      firestore
          .collection("calls")
          .doc(callId)
          .collection("calleeCandidates")
          .add(candidate.toMap());
    };

    // Check if an offer already exists
    final existing = await firestore.collection("calls").doc(callId).get();
    if (existing.exists && existing.data()?["offer"] != null) {
      print("📩 Child found existing offer, processing...");
      await _handleOffer(existing.data()!["offer"], callId);
    }

    // Listen for new offer
    _offerListener = firestore.collection("calls").doc(callId).snapshots().listen((snapshot) async {
      final data = snapshot.data();
      if (data == null) return;
      if (data["offer"] != null && !_answerSent) {
        print("📩 Child received offer via snapshot");
        await _handleOffer(data["offer"], callId);
      }
    });

    // Listen for remote ICE candidates (from parent)
    _candidateListener = firestore
        .collection("calls")
        .doc(callId)
        .collection("callerCandidates")
        .snapshots()
        .listen((snapshot) {
      for (var doc in snapshot.docs) {
        final data = doc.data();
        final candidate = RTCIceCandidate(
          data["candidate"],
          data["sdpMid"],
          data["sdpMLineIndex"],
        );
        if (_answerSent) {
          peerConnection?.addCandidate(candidate);
          print("📥 Child adding candidate (after answer)");
        } else {
          _pendingCandidates.add(candidate);
          print("📥 Child buffering candidate (waiting for answer)");
        }
      }
    });
  }

  Future<void> _handleOffer(Map<String, dynamic> offerMap, String callId) async {
    try {
      await peerConnection!.setRemoteDescription(
        RTCSessionDescription(offerMap["sdp"], offerMap["type"]),
      );
      print("✅ Child remote description set");

      final answer = await peerConnection!.createAnswer();
      await peerConnection!.setLocalDescription(answer);
      await firestore.collection("calls").doc(callId).update({
        "answer": answer.toMap()
      });
      _answerSent = true;

      for (var candidate in _pendingCandidates) {
        peerConnection?.addCandidate(candidate);
        print("📥 Child adding buffered candidate");
      }
      _pendingCandidates.clear();
      print("📤 Child answer sent");
    } catch (e) {
      print("❌ Child error handling offer: $e");
    }
  }

  Future<void> stop() async {
    _running = false;
    _answerSent = false;
    await _offerListener?.cancel();
    await _candidateListener?.cancel();
    _pendingCandidates.clear();
    await localStream?.dispose();
    await peerConnection?.close();
    localStream = null;
    peerConnection = null;
    print("🎤 Child mic stopped");
  }
}