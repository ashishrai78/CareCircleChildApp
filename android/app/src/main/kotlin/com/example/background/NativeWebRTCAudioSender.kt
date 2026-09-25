package com.example.background

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 🎤 NativeWebRTCAudioSender — Native WebRTC audio streaming (Stream WebRTC fork)
 *
 * Uses io.getstream:stream-webrtc-android (Maven Central, stable)
 *
 * Advantages over Flutter WebRTC:
 *  ✅ Runs in CareCircleForegroundService (stable, 24/7)
 *  ✅ Survives OEM kills (service auto-restarts)
 *  ✅ No Flutter isolate dependency
 *  ✅ State restoration on service restart
 *  ✅ Lower battery (no Flutter engine in background)
 *
 * Flow:
 *  1. Parent sets sync_mic=true + call_id in Firestore
 *  2. CareCircleForegroundService detects via checkSyncRequest()
 *  3. Calls NativeWebRTCAudioSender.start(callId)
 *  4. Creates PeerConnection + captures mic
 *  5. Listens for offer from Firestore (calls/{callId})
 *  6. Sends answer + ICE candidates
 *  7. Audio streams to parent via WebRTC
 *  8. When sync_mic=false → stop + cleanup
 */
class NativeWebRTCAudioSender(private val context: Context) {

    companion object {
        private const val TAG = "NativeWebRTC"

        // Firestore collections
        private const val CALLS_COLLECTION = "calls"
        private const val CALLER_CANDIDATES_SUB = "callerCandidates"
        private const val CALLEE_CANDIDATES_SUB = "calleeCandidates"

        @Volatile
        private var factoryInitialized = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firestore = FirebaseFirestore.getInstance()

    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    private var currentCallId: String? = null
    private var isRunning = false
    private var answerSent = false
    private val pendingCandidates = mutableListOf<IceCandidate>()

    // Firestore listeners
    private var offerListener: ListenerRegistration? = null
    private var candidateListener: ListenerRegistration? = null

    /**
     * Start WebRTC audio streaming
     * @param callId Unique session ID from parent
     */
    fun start(callId: String) {
        if (isRunning) {
            Log.w(TAG, "Already running for ${currentCallId} — skipping")
            return
        }

        Log.d(TAG, "🎤 Starting native WebRTC for call $callId")
        currentCallId = callId
        isRunning = true
        answerSent = false
        pendingCandidates.clear()

        scope.launch {
            try {
                // Step 1: Initialize PeerConnectionFactory (once)
                initializeFactory()

                // Step 2: Create PeerConnection
                createPeerConnection()

                // Step 3: Capture mic + add track
                captureMicAndAddTrack()

                // Step 4: Listen for offer
                listenForOffer(callId)

                // Step 5: Listen for caller's ICE candidates
                listenForCallerCandidates(callId)

                Log.d(TAG, "✅ WebRTC setup complete — waiting for parent offer")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Start failed: ${e.message}")
                cleanup()
                isRunning = false
                currentCallId = null
            }
        }
    }

    /**
     * Stop WebRTC and cleanup all resources
     */
    fun stop() {
        if (!isRunning) return

        Log.d(TAG, "🛑 Stopping native WebRTC for call $currentCallId")
        isRunning = false
        cleanup()
    }

    // ============ Initialization ============

    private fun initializeFactory() {
        if (factoryInitialized && factory != null) return

        try {
            // Create EglBase for video (needed for factory, even if audio only)
            eglBase = EglBase.create()

            // Initialize WebRTC
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )

            // Create audio device module
            val audioDevice = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                // 🔥 CRITICAL FIX: Use MIC source (1) instead of VOICE_COMMUNICATION (7)
                .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                .createAudioDeviceModule()

            // Create encoder/decoder factories
            val videoEncoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                false,
                false
            )
            val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDevice)
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory()

            factoryInitialized = true
            Log.d(TAG, "✅ PeerConnectionFactory initialized (AudioSource: MIC)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Factory init failed: ${e.message}")
            throw e
        }
    }

    private fun createPeerConnection() {
        // 🔥 Stream WebRTC uses IceTransportsType (not IceTransportPolicy)
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // sdpSemantics not needed in Stream WebRTC (UNIFIED_PLAN is default)
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        // 🔥 Add ICE servers to config
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
        // TURN servers (Metered — for NAT traversal)
        iceServers.add(
            PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
                .setUsername("bbcf61e1a367341798789c64")
                .setPassword("q+gj8mtw2NK43RPY")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443")
                .setUsername("bbcf61e1a367341798789c64")
                .setPassword("q+gj8mtw2NK43RPY")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
                .setUsername("bbcf61e1a367341798789c64")
                .setPassword("q+gj8mtw2NK43RPY")
                .createIceServer()
        )
        config.iceServers = iceServers

        peerConnection = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "🧊 ICE candidate generated")
                sendIceCandidate(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "🧊 ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Log.d(TAG, "✅ WebRTC Connected — audio streaming")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "⚠️ WebRTC Disconnected")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.e(TAG, "❌ WebRTC Failed")
                    }
                    else -> {}
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "🧊 ICE gathering: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "📡 Signaling: $state")
            }

            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        })

        if (peerConnection == null) {
            throw Exception("Failed to create PeerConnection")
        }

        Log.d(TAG, "✅ PeerConnection created")
    }

    private fun captureMicAndAddTrack() {
        try {
            // 🔥 FIX: Use MODE_NORMAL (NOT MODE_IN_COMMUNICATION)
            // MODE_IN_COMMUNICATION causes Android to upgrade MIC source to VOICE_COMMUNICATION
            // which is BLOCKED in background on Android 10+
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_NORMAL

            Log.d(TAG, "🔊 Audio mode: NORMAL (MIC source will be used)")

            // Audio constraints — RAW ambient audio
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            }

            audioSource = factory?.createAudioSource(constraints)
            audioTrack = factory?.createAudioTrack("audio_track", audioSource)
            peerConnection?.addTrack(audioTrack, listOf("stream_id"))

            Log.d(TAG, "✅ Mic captured + track added")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Mic capture failed: ${e.message}")
            throw e
        }
    }

    // ============ Firestore Signaling ============

    private fun listenForOffer(callId: String) {
        val docRef = firestore.collection(CALLS_COLLECTION).document(callId)

        offerListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "❌ Offer listener error: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

            val data = snapshot.data ?: return@addSnapshotListener
            val offer = data["offer"] as? Map<String, Any>

            if (offer != null && !answerSent) {
                Log.d(TAG, "📩 Received offer via snapshot")
                handleOffer(offer, callId)
            }
        }

        // Also check for existing offer immediately
        scope.launch {
            try {
                val doc = withTimeoutOrNull(5_000) { docRef.get().await() }
                if (doc != null && doc.exists()) {
                    val data = doc.data
                    val offer = data?.get("offer") as? Map<String, Any>
                    if (offer != null && !answerSent) {
                        Log.d(TAG, "📩 Found existing offer")
                        handleOffer(offer, callId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Existing offer check failed: ${e.message}")
            }
        }
    }

    private fun listenForCallerCandidates(callId: String) {
        val collectionRef = firestore.collection(CALLS_COLLECTION)
            .document(callId)
            .collection(CALLER_CANDIDATES_SUB)

        candidateListener = collectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "❌ Candidate listener error: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot == null) return@addSnapshotListener

            for (change in snapshot.documentChanges) {
                if (change.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) continue

                val data = change.document.data
                val candidate = IceCandidate(
                    data["sdpMid"] as String?,
                    (data["sdpMLineIndex"] as Long?)?.toInt() ?: 0,
                    data["candidate"] as String?
                )

                if (answerSent && peerConnection != null) {
                    try {
                        peerConnection?.addIceCandidate(candidate)
                        Log.d(TAG, "🧊 Added candidate (after answer)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Add candidate failed: ${e.message}")
                    }
                } else {
                    pendingCandidates.add(candidate)
                    Log.d(TAG, "🧊 Buffered candidate (waiting for answer)")
                }
            }
        }
    }

    private fun handleOffer(offerMap: Map<String, Any>, callId: String) {
        if (answerSent || peerConnection == null) return

        scope.launch {
            try {
                val sdp = offerMap["sdp"] as? String
                val type = offerMap["type"] as? String

                if (sdp == null || type == null) {
                    Log.e(TAG, "❌ Invalid offer — missing sdp/type")
                    return@launch
                }

                // 🔥 Stream WebRTC: use SessionDescription.Type directly
                val sdpType = when (type.lowercase()) {
                    "offer" -> SessionDescription.Type.OFFER
                    "answer" -> SessionDescription.Type.ANSWER
                    "pranswer" -> SessionDescription.Type.PRANSWER
                    else -> SessionDescription.Type.OFFER
                }

                // Set remote description (parent's offer)
                val remoteSdp = SessionDescription(sdpType, sdp)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "❌ Set remote failed: $p0")
                    }
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Remote description set")
                        createAndSendAnswer(callId)
                    }
                    override fun onCreateFailure(p0: String?) {}
                }, remoteSdp)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Handle offer failed: ${e.message}")
            }
        }
    }

    private fun createAndSendAnswer(callId: String) {
        scope.launch {
            try {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return

                        // Set local description
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetFailure(p0: String?) {
                                Log.e(TAG, "❌ Set local failed: $p0")
                            }
                            override fun onSetSuccess() {
                                Log.d(TAG, "✅ Local description set")
                                sendAnswerToFirestore(answer, callId)
                            }
                            override fun onCreateFailure(p0: String?) {}
                        }, answer)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "❌ Create answer failed: $error")
                    }
                    override fun onSetFailure(error: String?) {}
                }, constraints)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Create answer exception: ${e.message}")
            }
        }
    }

    private fun sendAnswerToFirestore(answer: SessionDescription, callId: String) {
        scope.launch {
            try {
                // 🔥 Stream WebRTC: use canonicalForm() for type
                val answerMap = mapOf(
                    "type" to answer.type.canonicalForm(),
                    "sdp" to answer.description
                )

                firestore.collection(CALLS_COLLECTION)
                    .document(callId)
                    .update("answer", answerMap)
                    .await()

                answerSent = true
                Log.d(TAG, "📤 Answer sent to Firestore")

                // Flush buffered ICE candidates
                for (candidate in pendingCandidates) {
                    try {
                        peerConnection?.addIceCandidate(candidate)
                        Log.d(TAG, "🧊 Flushed buffered candidate")
                    } catch (e: Exception) {
                        Log.w(TAG, "Flush candidate failed: ${e.message}")
                    }
                }
                pendingCandidates.clear()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Send answer failed: ${e.message}")
            }
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val callId = currentCallId ?: return

        scope.launch {
            try {
                val candidateMap = mapOf(
                    "candidate" to candidate.sdp,
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex
                )

                firestore.collection(CALLS_COLLECTION)
                    .document(callId)
                    .collection(CALLEE_CANDIDATES_SUB)
                    .add(candidateMap)
                    .await()

                Log.d(TAG, "📤 ICE candidate sent")
            } catch (e: Exception) {
                Log.w(TAG, "Send ICE candidate failed: ${e.message}")
            }
        }
    }

    // ============ Cleanup ============

    private fun cleanup() {
        try {
            offerListener?.remove()
            offerListener = null

            candidateListener?.remove()
            candidateListener = null

            pendingCandidates.clear()
            answerSent = false

            try {
                audioTrack?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Audio track dispose error: ${e.message}")
            }
            audioTrack = null

            try {
                audioSource?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Audio source dispose error: ${e.message}")
            }
            audioSource = null

            try {
                peerConnection?.close()
                peerConnection?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "PC close error: ${e.message}")
            }
            peerConnection = null

            try {
                eglBase?.release()
            } catch (e: Exception) {
                Log.w(TAG, "EglBase release error: ${e.message}")
            }
            eglBase = null

            // 🔥 Revert audio mode to NORMAL
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
                Log.d(TAG, "🔊 Audio mode reverted to NORMAL")
            } catch (e: Exception) {
                Log.w(TAG, "Audio mode revert failed: ${e.message}")
            }

            currentCallId = null
            Log.d(TAG, "✅ Cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    /**
     * Check if WebRTC is currently running
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get current call ID
     */
    fun getCurrentCallId(): String? = currentCallId

    /**
     * Restore previous WebRTC session (called on service restart)
     */
    fun restoreIfNeeded(webrtcRunning: Boolean, callId: String?) {
        if (webrtcRunning && !callId.isNullOrEmpty()) {
            Log.d(TAG, "🔄 Restoring WebRTC session: $callId")
            start(callId)
        }
    }
}
