package com.example.background

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 🎤 NativeWebRTCAudioSender — PATCHED v2 (Stream WebRTC fork)
 *
 * Tumhare current code ka signaling flow BILKUL same hai (calls/{callId},
 * offer listener + immediate get, answer via canonicalForm, caller/callee
 * candidates, buffered ICE). Sirf ye fixes add hue:
 *
 * ⭐ FIX #1 (SILENCE DETECTION — mic-block pakadna):
 *   AudioRecordStateCallback sirf "STARTED" log karta hai — jab mic
 *   OS-blocked hota hai tab bhi ye print hota hai (koi exception nahi,
 *   sirf zeros milte hai). Ab setSamplesReadyCallback se ACTUAL PCM
 *   samples ka RMS compute hota hai:
 *     - RMS ~0 (pure zeros) = mic blocked
 *     - RMS > 40 = real ambient audio
 *   Service ka watchdog isko use karke auto-heal karta hai.
 *
 * ⭐ FIX #2 (ADM LEAK):
 *   JavaAudioDeviceModule factory.dispose() se release NAHI hota.
 *   Pehle reference hi nahi rakha jata tha → har session pe audio
 *   thread + AudioRecord leak. Ab reference held + cleanup() me release.
 *
 * ⭐ FIX #3 (ICE RECOVERY):
 *   FAILED → turant restartIce(); DISCONNECTED → 10s wait, phir bhi
 *   disconnected to restartIce(). Pehle sirf log hota tha.
 *
 * ⭐ FIX #4 (CALL WAKELOCK): 15 min → 2 hr cap (lambi listening session
 *   me CPU sleep na ho; master service ka indefinite wakelock hata diya
 *   gaya hai — battery fix).
 *
 * ⭐ FIX #5 (BITRATE CAP): Opus 24kbps — ambient listening ke liye kaafi,
 *   data/battery bachta hai.
 *
 * ⚠️⚠️ SECURITY (TURANT KARO) ⚠️⚠️
 *   TURN credentials tumhare code me hard-coded hai aur ab PUBLIC ho
 *   chuke hai (chat me paste hua). Metered dashboard se ROTATE karo aur
 *   niche constants update karo.
 */
class NativeWebRTCAudioSender(private val context: Context) {

    companion object {
        private const val TAG = "NativeWebRTC"

        // Firestore collections
        private const val CALLS_COLLECTION = "calls"
        private const val CALLER_CANDIDATES_SUB = "callerCandidates"
        private const val CALLEE_CANDIDATES_SUB = "calleeCandidates"

        // ---- ⭐ FIX #1: Silence watchdog tuning ----
        // OS-blocked mic EXACT zeros deta hai (RMS ~0). Quiet room ka noise
        // floor bhi 16-bit PCM me RMS 40+ hota hai. RMS <= 40 = silent.
        private const val SILENCE_RMS_THRESHOLD = 40.0
        private const val SILENCE_WINDOW_MS = 10_000L

        // ⭐ FIX #4: call wakelock — lambi session ke liye 2hr cap
        private const val CALL_WAKELOCK_MS = 2 * 60 * 60 * 1000L

        // ⭐ FIX #5: Opus bitrate cap
        private const val MAX_AUDIO_BITRATE_BPS = 24_000

        // ============================================================
        // ⚠️ TURN CREDENTIALS — METERED DASHBOARD SE ROTATE KARKE YAHAN DAALO
        // (purane credentials public ho chuke hai — turant badlo!)
        // ============================================================
        private const val TURN_USERNAME = "bbcf61e1a367341798789c64"
        private const val TURN_PASSWORD = "q+gj8mtw2NK43RPY"

        // ICE recovery delay (DISCONNECTED ke baad)
        private const val ICE_RECOVERY_DELAY_MS = 10_000L
    }

    @Volatile
    private var factoryInitialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firestore = FirebaseFirestore.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var audioRtpSender: RtpSender? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    // ⭐ FIX #2: ADM reference held — cleanup me release() ZAROORI
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    private var currentCallId: String? = null
    private var isRunning = false
    private var answerSent = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var callWakeLock: PowerManager.WakeLock? = null

    // ⭐ FIX #1: silence tracking (audio thread se update hota hai)
    @Volatile
    private var lastNonSilentTime = 0L

    // ⭐ FIX #3: ICE state tracking
    @Volatile
    private var lastIceState: PeerConnection.IceConnectionState? = null

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
        lastNonSilentTime = System.currentTimeMillis()
        lastIceState = null

        // ⭐ FIX #4: dedicated WakeLock — 2hr cap (lambi session safe)
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            callWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CareCircle:WebRTCAudioCall")
            callWakeLock?.acquire(CALL_WAKELOCK_MS)
            Log.d(TAG, "✅ Call WakeLock acquired (${CALL_WAKELOCK_MS / 60000}min cap)")
        } catch (e: Exception) {
            Log.w(TAG, "Call WakeLock acquire failed: ${e.message}")
        }

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

    // ============ ⭐ FIX #1: Silence Detection (actual PCM RMS) ============

    /**
     * WebRTC har PCM16 frame ke saath yahan bulata hai.
     * OS-blocked mic EXACT zeros deta hai → RMS ~0 = blocked.
     */
    private fun onAudioSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        if (!isRunning) return
        val data = samples.data ?: return
        val numSamples = data.size / 2
        if (numSamples == 0) return

        try {
            var sum = 0.0
            for (i in 0 until numSamples) {
                val idx = i * 2
                val low = data[idx].toInt() and 0xFF
                val high = data[idx + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toDouble()
                sum += sample * sample
            }
            val rms = kotlin.math.sqrt(sum / numSamples)
            if (rms > SILENCE_RMS_THRESHOLD) {
                // Real audio mila — watchdog window reset
                lastNonSilentTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            // Non-PCM16 frame — ignore
        }
    }

    /**
     * ⭐ Service watchdog ise use karta hai.
     * TRUE = WebRTC connected AND mic 10s+ se silence (zeros) = blocked mic.
     */
    fun isWatchdogSuspect(): Boolean {
        if (!isRunning) return false
        if (!isConnectionAlive()) return false
        return System.currentTimeMillis() - lastNonSilentTime > SILENCE_WINDOW_MS
    }

    fun isConnectionAlive(): Boolean =
        lastIceState == PeerConnection.IceConnectionState.CONNECTED ||
                lastIceState == PeerConnection.IceConnectionState.COMPLETED

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

            // ⭐ FIX #1: setSamplesReadyCallback — ACTUAL samples ka RMS check.
            // AudioRecordStateCallback misleading hai (blocked mic pe bhi
            // "STARTED" print hota hai).
            val audioDevice = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                // 🔥 CRITICAL FIX: Use MIC source (1) instead of VOICE_COMMUNICATION (7)
                .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                .setSamplesReadyCallback { samples -> onAudioSamples(samples) }
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.e(TAG, "❌ AudioRecord init error: $errorMessage")
                    }
                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.e(TAG, "❌ AudioRecord start error: $errorCode - $errorMessage")
                    }
                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.e(TAG, "❌ AudioRecord error: $errorMessage")
                    }
                })
                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() {
                        Log.d(TAG, "🎙️ AudioRecord STARTED (note: blocked mic pe bhi ye aata hai)")
                        lastNonSilentTime = System.currentTimeMillis()
                    }
                    override fun onWebRtcAudioRecordStop() {
                        Log.d(TAG, "🎙️ AudioRecord STOPPED recording")
                    }
                })
                .createAudioDeviceModule()

            // ⭐ FIX #2: ADM reference hold karo — cleanup me release() hoga
            audioDeviceModule = audioDevice

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
            Log.d(TAG, "✅ PeerConnectionFactory initialized (AudioSource: MIC, silence watchdog ON)")
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
        // ⚠️ Credentials ROTATE ho chuki honi chahiye (upar constants)
        iceServers.add(
            PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_PASSWORD)
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443")
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_PASSWORD)
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_PASSWORD)
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
                lastIceState = state
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        Log.d(TAG, "✅ WebRTC Connected — audio streaming")
                        // ⭐ FIX #1: watchdog window connection se reset —
                        // connect hone me lage time ko silence na gine
                        lastNonSilentTime = System.currentTimeMillis()
                        applyAudioBitrateCap()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "⚠️ WebRTC Disconnected — 10s me recover na ho to ICE restart")
                        scheduleIceRecovery()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        // ⭐ FIX #3: pehle sirf log hota tha — ab turant recovery
                        Log.e(TAG, "❌ WebRTC FAILED — restartIce() call kar rahe hai")
                        restartIceNow()
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

    /** ⭐ FIX #3: DISCONNECTED ke 10s baad bhi wahi state to ICE restart */
    private fun scheduleIceRecovery() {
        mainHandler.postDelayed({
            if (isRunning && lastIceState == PeerConnection.IceConnectionState.DISCONNECTED) {
                Log.w(TAG, "🔄 Abhi bhi DISCONNECTED — restartIce()")
                restartIceNow()
            }
        }, ICE_RECOVERY_DELAY_MS)
    }

    private fun restartIceNow() {
        try {
            peerConnection?.restartIce()
        } catch (e: Exception) {
            Log.e(TAG, "restartIce failed: ${e.message}")
        }
    }

    private fun captureMicAndAddTrack() {
        try {
            // 🔥 FIX: Use MODE_NORMAL (NOT MODE_IN_COMMUNICATION)
            // MODE_IN_COMMUNICATION causes Android to upgrade MIC source to VOICE_COMMUNICATION
            // which is BLOCKED in background on Android 10+
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            if (audioManager.isMicrophoneMute) {
                audioManager.isMicrophoneMute = false
                Log.d(TAG, "🔊 Unmuted microphone")
            }

            // Request audio focus to ensure Android routes mic to our app
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    audioManager.requestAudioFocus(focusRequest)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio focus request error: ${e.message}")
            }

            Log.d(TAG, "🔊 Audio mode: NORMAL (MIC source will be used)")

            // Audio constraints — RAW ambient audio
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            }

            audioSource = factory?.createAudioSource(constraints)
            audioTrack = factory?.createAudioTrack("audio_track", audioSource)
            audioTrack?.setEnabled(true)
            audioTrack?.setVolume(1.0)
            audioRtpSender = peerConnection?.addTrack(audioTrack, listOf("stream_id"))

            Log.d(TAG, "✅ Mic captured + track added (enabled=true)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Mic capture failed: ${e.message}")
            throw e
        }
    }

    /** ⭐ FIX #5: Opus 24kbps cap — answer set hone ke baad apply hota hai */
    private fun applyAudioBitrateCap() {
        try {
            val sender = audioRtpSender ?: return
            val params = sender.parameters
            var changed = false
            for (enc in params.encodings) {
                val current = enc.maxBitrateBps
                if (current == null || current > MAX_AUDIO_BITRATE_BPS) {
                    enc.maxBitrateBps = MAX_AUDIO_BITRATE_BPS
                    changed = true
                }
            }
            if (changed) {
                sender.parameters = params
                Log.d(TAG, "✅ Audio bitrate cap ${MAX_AUDIO_BITRATE_BPS / 1000}kbps applied")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bitrate cap failed: ${e.message}")
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

                // ⭐ FIX #5: bitrate cap ab apply kar sakte hai (negotiation done)
                applyAudioBitrateCap()

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
            // ⭐ ICE recovery ke pending handler posts hatao
            mainHandler.removeCallbacksAndMessages(null)

            offerListener?.remove()
            offerListener = null

            candidateListener?.remove()
            candidateListener = null

            pendingCandidates.clear()
            answerSent = false

            try {
                audioTrack?.setEnabled(false)
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
            audioRtpSender = null

            try {
                peerConnection?.close()
                peerConnection?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "PC close error: ${e.message}")
            }
            peerConnection = null

            try {
                factory?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Factory dispose error: ${e.message}")
            }
            factory = null
            factoryInitialized = false

            // ⭐ FIX #2: ADM release — factory.dispose() ADM ko release NAHI karta.
            // Ye reh gaya to har session pe audio thread + AudioRecord LEAK hota
            // (phle iska reference bhi nahi rakha jata tha).
            try {
                audioDeviceModule?.release()
            } catch (e: Exception) {
                Log.w(TAG, "ADM release error: ${e.message}")
            }
            audioDeviceModule = null

            try {
                eglBase?.release()
            } catch (e: Exception) {
                Log.w(TAG, "EglBase release error: ${e.message}")
            }
            eglBase = null

            // 🔥 Release call WakeLock
            try {
                if (callWakeLock?.isHeld == true) {
                    callWakeLock?.release()
                    Log.d(TAG, "Call WakeLock released")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Call WakeLock release error: ${e.message}")
            }
            callWakeLock = null

            // 🔥 Revert audio mode to NORMAL
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
                Log.d(TAG, "🔊 Audio mode reverted to NORMAL")
            } catch (e: Exception) {
                Log.w(TAG, "Audio mode revert failed: ${e.message}")
            }

            lastIceState = null
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
}
