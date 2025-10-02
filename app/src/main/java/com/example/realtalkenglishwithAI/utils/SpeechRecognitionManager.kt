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

// This class now requires Android 6.0 (API 23) or higher to function correctly.
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

    enum class State {
        IDLE, LISTENING, ERROR
    }

    // Reverted to a two-tier strategy as requested.
    private enum class BeepSuppressionStrategy { DEFAULT, HEAVY_DUTY }

    private var speechRecognizer: SpeechRecognizer? = null
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val speechRecognizerIntent: Intent
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isListeningActive = false
    private var beepStrategy: BeepSuppressionStrategy
    @Volatile private var lastStartTime = 0L
    @Volatile private var isMuted = false
    private var quickFailureCount = 0
    private val shutdownHook: Thread

    val isAvailable: Boolean

    init {
        beepStrategy = getSavedStrategy()
        Log.d(TAG, "Initializing with strategy: $beepStrategy")

        shutdownHook = Thread { unmuteAllBeepStreams() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (isAvailable) {
            mainHandler.post {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(recognitionListener)
                }
            }
        }
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
    }

    fun startListening() {
        if (isListeningActive) return
        isListeningActive = true
        quickFailureCount = 0 // Reset counter on new start
        internalStart()
    }

    fun stopListening() {
        if (!isListeningActive) return
        isListeningActive = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { speechRecognizer?.stopListening() }
        unmuteAllBeepStreams()
        listener.onStateChanged(State.IDLE)
    }

    fun destroy() {
        isListeningActive = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { speechRecognizer?.destroy() }
        unmuteAllBeepStreams()

        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (e: IllegalStateException) {
            // Ignore, VM is already shutting down.
        }
    }

    private fun internalStart() {
        mainHandler.post {
            if (!isAvailable || !isListeningActive) return@post
            lastStartTime = System.currentTimeMillis()
            listener.onStateChanged(State.LISTENING)
            muteBeep()

            try {
                speechRecognizer?.startListening(speechRecognizerIntent)
            } catch (e: Exception) {
                Log.e(TAG, "startListening failed", e)
                unmuteAllBeepStreams()
            }
        }
    }

    private fun restartListeningLoop() {
        if (isListeningActive) {
             internalStart()
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech: streams will remain muted.")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer:ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (!isListeningActive) {
                unmuteAllBeepStreams()
                return
            }

            val isRecoverable = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
            if (isRecoverable) {
                val timeSinceStart = System.currentTimeMillis() - lastStartTime
                // Escalate from DEFAULT to HEAVY_DUTY if needed.
                if (beepStrategy != BeepSuppressionStrategy.HEAVY_DUTY &&
                    timeSinceStart < 1500) { // Quick failure
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
                restartListeningLoop()
            } else {
                Log.e(TAG, "Critical speech error: $error. Shutting down.")
                unmuteAllBeepStreams()
                isListeningActive = false
                listener.onError(error, isCritical = true)
                listener.onStateChanged(State.ERROR)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!isListeningActive) return
            quickFailureCount = 0

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                listener.onFinalResults(matches[0])
            }
            restartListeningLoop()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isListeningActive) return
            quickFailureCount = 0

            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                listener.onPartialResults(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun muteBeep() {
        if (isMuted) return
        Log.d(TAG, "Muting streams with strategy: $beepStrategy")
        try {
            // DEFAULT strategy mutes NOTIFICATION.
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)

            // HEAVY_DUTY also mutes SYSTEM.
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
            // We no longer touch STREAM_MUSIC, but unmuting it here is safe and ensures a clean state.
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