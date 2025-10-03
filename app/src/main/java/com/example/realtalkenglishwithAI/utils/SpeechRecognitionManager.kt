package com.example.realtalkenglishwithAI.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechRecognitionManager(
    private val context: Context,
    private val listener: SpeechRecognitionManagerListener,
    private val prefs: SharedPreferences
) {

    interface SpeechRecognitionManagerListener {
        fun onPartialResults(transcript: String)
        fun onFinalResults(transcript: String)
        fun onError(error: Int, isCritical: Boolean)
        fun onStateChanged(state: State)
    }

    enum class State { IDLE, LISTENING, ERROR }
    private enum class BeepSuppressionStrategy { DEFAULT, HEAVY_DUTY }

    // --- Properties ---
    private var speechRecognizer: SpeechRecognizer? = null
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Biasing and graceful restart properties
    private var biasingStrings: List<String> = emptyList()
    @Volatile private var isBiasingSupported: Boolean
    @Volatile private var isPerformingGracefulRestart = false
    @Volatile private var busyErrorCounter = 0
    private val busyErrorResetHandler = Handler(Looper.getMainLooper())

    @Volatile private var isListeningActive = false
    private var beepStrategy: BeepSuppressionStrategy
    @Volatile private var lastStartTime = 0L
    @Volatile private var isMuted = false
    private var quickFailureCount = 0

    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    init {
        beepStrategy = getSavedStrategy()
        // Load the learned state for biasing support.
        isBiasingSupported = prefs.getBoolean(PREF_KEY_BIASING_SUPPORT, true)
        Log.d(TAG, "Initializing with strategy: $beepStrategy. Biasing support: $isBiasingSupported")

        if (isAvailable) {
            mainHandler.post {
                createRecognizer()
            }
        }
    }

    private fun createRecognizer() {
        Log.d(TAG, "Creating new SpeechRecognizer instance.")
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    // --- Public Control Methods ---
    fun startListening() {
        if (isListeningActive) return
        Log.d(TAG, "startListening: User initiated start.")
        isListeningActive = true
        quickFailureCount = 0
        busyErrorCounter = 0
        internalStart()
    }

    fun stopListening() {
        if (!isListeningActive) return
        Log.d(TAG, "stopListening: User initiated stop.")
        isListeningActive = false
        isPerformingGracefulRestart = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { speechRecognizer?.cancel() } // Use cancel for faster stop
        unmuteAllBeepStreams()
        listener.onStateChanged(State.IDLE)
    }

    fun destroy() {
        Log.d(TAG, "destroy: Cleaning up SpeechRecognitionManager.")
        isListeningActive = false
        isPerformingGracefulRestart = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { speechRecognizer?.destroy() }
        unmuteAllBeepStreams()
    }

    fun updateBiasingStrings(newStrings: List<String>) {
        val distinctNewStrings = newStrings.distinct()
        if (biasingStrings == distinctNewStrings) return

        biasingStrings = distinctNewStrings
        Log.d(TAG, "Updating ASR bias list with ${biasingStrings.size} words.")

        // Only restart if the feature is active and supported.
        if (isListeningActive && isBiasingSupported) {
            restartListeningGracefully()
        } else if (isListeningActive) {
            Log.d(TAG, "Biasing support is disabled, skipping graceful restart.")
        }
    }

    // --- Internal Logic ---
    private fun internalStart() {
        mainHandler.post {
            if (!isAvailable || !isListeningActive) return@post
            lastStartTime = System.currentTimeMillis()
            listener.onStateChanged(State.LISTENING)
            muteBeep()

            try {
                speechRecognizer?.startListening(getRecognizerIntentWithBias())
            } catch (e: Exception) {
                Log.e(TAG, "startListening failed", e)
                unmuteAllBeepStreams()
            }
        }
    }

    private fun getRecognizerIntentWithBias(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)

            // Only add biasing strings if the feature is currently enabled.
            if (isBiasingSupported && biasingStrings.isNotEmpty()) {
                Log.d(TAG, "Applying biasing strings to intent.")
                putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", ArrayList(biasingStrings))
            }
        }
    }

    private fun restartListeningLoop() {
        if (isListeningActive) {
            internalStart()
        }
    }

    private fun restartListeningGracefully() {
        if (!isListeningActive || !isBiasingSupported) return

        mainHandler.post {
            Log.d(TAG, "Initiating graceful restart for biasing...")
            isPerformingGracefulRestart = true
            speechRecognizer?.cancel() // This will trigger onError with ERROR_CLIENT
        }
    }

    // --- Recognition Listener ---
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech: streams will remain muted.")
            isPerformingGracefulRestart = false // It's ready, so we are no longer in the restart process
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (!isListeningActive) {
                unmuteAllBeepStreams()
                return
            }

            // Case 1: This is our own cancellation during a graceful restart for biasing.
            if (isPerformingGracefulRestart && error == SpeechRecognizer.ERROR_CLIENT) {
                Log.d(TAG, "Graceful restart: Ignored expected ERROR_CLIENT. Restarting after delay.")
                isPerformingGracefulRestart = false
                mainHandler.postDelayed({ restartListeningLoop() }, 450L) // Crucial delay
                return
            }
            isPerformingGracefulRestart = false // Reset flag on any other error

            // Case 2: The recognizer is busy. THIS IS THE KEY SYMPTOM for faulty biasing.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                busyErrorCounter++
                Log.w(TAG, "Recognizer busy. Consecutive error count: $busyErrorCounter")

                // If we get too many busy errors, disable the biasing feature permanently.
                if (isBiasingSupported && busyErrorCounter >= 1) {
                    Log.e(TAG, "Too many consecutive busy errors. Disabling biasing feature permanently.")
                    isBiasingSupported = false
                    prefs.edit().putBoolean(PREF_KEY_BIASING_SUPPORT, false).apply()
                    // Retry one last time, now without biasing.
                    restartListeningLoop()
                    return
                }
                // Otherwise, just retry after a short delay.
                mainHandler.postDelayed({ restartListeningLoop() }, 500L)
                return
            }

            // If we reach here, it's not a busy error, so reset the counter.
            resetBusyCounter()

            // Case 3: A recoverable error (timeout, no match), from your original logic.
            val isRecoverable = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
            if (isRecoverable) {
                handleRecoverableError()
                restartListeningLoop()
                return
            }

            // Case 4: A critical, non-recoverable error.
            Log.e(TAG, "Critical speech error: $error. Shutting down.")
            unmuteAllBeepStreams()
            isListeningActive = false
            listener.onError(error, isCritical = true)
            listener.onStateChanged(State.ERROR)
        }

        override fun onResults(results: Bundle?) {
            if (!isListeningActive) return
            resetAllCounters()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                listener.onFinalResults(matches[0])
            }
            restartListeningLoop()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isListeningActive) return
            resetAllCounters()

            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                listener.onPartialResults(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // --- Helper functions for error/state handling ---
    private fun resetBusyCounter() {
        if (busyErrorCounter > 0) {
            Log.d(TAG, "Resetting busy error counter.")
            busyErrorCounter = 0
        }
    }

    private fun resetAllCounters() {
        resetBusyCounter()
        quickFailureCount = 0
    }

    private fun handleRecoverableError() {
        Log.d(TAG, "Handling recoverable error.")
        val timeSinceStart = System.currentTimeMillis() - lastStartTime
        if (beepStrategy != BeepSuppressionStrategy.HEAVY_DUTY && timeSinceStart < 1500) { // Quick failure
            quickFailureCount++
            if (quickFailureCount >= 2) {
                Log.w(TAG, "Consecutive quick failures detected. Escalating to HEAVY_DUTY strategy.")
                beepStrategy = BeepSuppressionStrategy.HEAVY_DUTY
                saveStrategy(beepStrategy)
                quickFailureCount = 0
            }
        } else {
            quickFailureCount = 0
        }
    }

    // --- Beep Suppression Logic ---
    private fun muteBeep() {
        if (isMuted) return
        Log.d(TAG, "Muting streams with strategy: $beepStrategy")
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            if (beepStrategy == BeepSuppressionStrategy.HEAVY_DUTY) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            }
            isMuted = true
        } catch (se: SecurityException) {
            Log.w(TAG, "Mute not allowed by system policy. Beep may be audible.", se)
        }
    }

    private fun unmuteAllBeepStreams() {
        if (!isMuted) return
        Log.d(TAG, "Unmuting all streams.")
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            isMuted = false
        } catch (se: SecurityException) {
            Log.w(TAG, "Unmute not allowed by system policy.", se)
        }
    }

    private fun getSavedStrategy(): BeepSuppressionStrategy {
        val strategyName = prefs.getString(PREF_KEY_STRATEGY, BeepSuppressionStrategy.DEFAULT.name)
        return try {
            BeepSuppressionStrategy.valueOf(strategyName ?: BeepSuppressionStrategy.DEFAULT.name)
        } catch (e: IllegalArgumentException) {
            BeepSuppressionStrategy.DEFAULT
        }
    }

    private fun saveStrategy(strategy: BeepSuppressionStrategy) {
        prefs.edit().putString(PREF_KEY_STRATEGY, strategy.name).apply()
    }

    companion object {
        private const val TAG = "SpeechRecManager"
        private const val PREF_KEY_STRATEGY = "beep_suppression_strategy"
        private const val PREF_KEY_BIASING_SUPPORT = "biasing_feature_supported"

        fun getErrorText(error: Int): String {
            return when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                else -> "Unknown speech error"
            }
        }
    }
}
