package com.example.realtalkenglishwithAI.utils

import android.util.Log
import com.example.realtalkenglishwithAI.fragment.StoryReadingFragment
import org.apache.commons.codec.language.DoubleMetaphone
import kotlin.math.min

class RealtimeAlignmentProcessor(private val levenshteinProcessor: LevenshteinProcessor) {

    private val metaphoneEncoder = DoubleMetaphone()

    // --- State for Consecutive Lock ---
    @Volatile private var pendingLockIndex: Int = -1
    @Volatile private var pendingConsecutiveCount: Int = 0

    data class AlignmentUpdateResult(
        val newLockedGlobalIndex: Int,
        val newProcessedWords: List<String>,
        val dirtySentences: Set<Int>,
        val newFocusCandidate: Int
    )

    data class LockingParams(
        val highConfidenceRatio: Float,
        val requiredConsecutiveMatches: Int
    )

    fun process(
        partialTranscript: String,
        lastProcessedWords: List<String>,
        lastLockedGlobalIndex: Int,
        allWordInfosInStory: List<StoryReadingFragment.WordInfo>,
        difficultyLevel: Int,
        isUserRecording: Boolean
    ): AlignmentUpdateResult {

        val currentRecognizedWords = partialTranscript.trim().split(Regex("\\s+"))
            .map { normalizeToken(it) }.filter { it.isNotBlank() }

        var currentLastProcessedWords = lastProcessedWords
        if (currentRecognizedWords.size < currentLastProcessedWords.size) {
            currentLastProcessedWords = emptyList()
            resetPendingLock()
        }

        val dirtySentences = mutableSetOf<Int>()
        var currentLockedGlobalIndex = lastLockedGlobalIndex

        var transcriptStartIndex = 0
        val searchLimit = min(currentLastProcessedWords.size, currentRecognizedWords.size)
        while (transcriptStartIndex < searchLimit && currentLastProcessedWords[transcriptStartIndex] == currentRecognizedWords[transcriptStartIndex]) {
            transcriptStartIndex++
        }

        var storyIndex = lastLockedGlobalIndex + 1
        var transcriptIndex = transcriptStartIndex

        val params = getLockingParams(difficultyLevel)

        while (storyIndex < allWordInfosInStory.size && transcriptIndex < currentRecognizedWords.size) {
            val storyWordInfo = allWordInfosInStory[storyIndex]
            val recogWord = currentRecognizedWords[transcriptIndex]

            val isMatch = isWordMatch(storyWordInfo, recogWord, difficultyLevel)

            if (isMatch) {
                if (storyWordInfo.status != StoryReadingFragment.WordMatchStatus.CORRECT) {
                    storyWordInfo.status = StoryReadingFragment.WordMatchStatus.CORRECT
                    dirtySentences.add(storyWordInfo.sentenceIndex)
                }

                val sim = normalizedSimilarity(storyWordInfo.normalizedText, recogWord)

                if (ENABLE_CONSECUTIVE_DEBUG) {
                    Log.d("ConsecDebug", "storyIdx=$storyIndex recWord='$recogWord' sim=${String.format("%.2f", sim)} pending=$pendingLockIndex count=$pendingConsecutiveCount")
                }

                if (sim >= params.highConfidenceRatio) {
                    currentLockedGlobalIndex = storyIndex
                    resetPendingLock()
                    if (ENABLE_CONSECUTIVE_DEBUG) Log.d("ConsecDebug", "LOCK at index $storyIndex (High Sim)")
                } else {
                    if (pendingLockIndex == storyIndex) {
                        pendingConsecutiveCount++
                    } else {
                        pendingLockIndex = storyIndex
                        pendingConsecutiveCount = 1
                    }

                    if (pendingConsecutiveCount >= params.requiredConsecutiveMatches) {
                        currentLockedGlobalIndex = storyIndex
                        resetPendingLock()
                        if (ENABLE_CONSECUTIVE_DEBUG) Log.d("ConsecDebug", "LOCK at index $storyIndex (Consecutive)")
                    } else {
                        if (ENABLE_CONSECUTIVE_DEBUG) Log.d("ConsecDebug", "PENDING lock at $storyIndex, count=$pendingConsecutiveCount")
                    }
                }

                storyIndex++
                transcriptIndex++

            } else {
                // **THE ULTIMATE PATIENCE**
                // On any mismatch, reset any pending lock and STOP processing this transcript.
                // Wait for the next partial result to get a better signal.
                if (pendingLockIndex != -1) {
                    if (ENABLE_CONSECUTIVE_DEBUG) Log.d("ConsecDebug", "RESET pending lock due to mismatch.")
                    resetPendingLock()
                }
                break // Stop immediately and wait for the next onPartialResults call
            }
        }

        val newFocusCandidate = if (isUserRecording && currentLockedGlobalIndex + 1 < allWordInfosInStory.size) {
            currentLockedGlobalIndex + 1
        } else {
            -1
        }

        return AlignmentUpdateResult(currentLockedGlobalIndex, currentRecognizedWords, dirtySentences, newFocusCandidate)
    }

    fun reset() {
        resetPendingLock()
    }

    private fun resetPendingLock() {
        pendingLockIndex = -1
        pendingConsecutiveCount = 0
    }

    private fun isWordMatch(storyWordInfo: StoryReadingFragment.WordInfo, recogWord: String, difficulty: Int): Boolean {
        // Lazily encode the recognized word's metaphone only when needed for medium or easy modes.
        val recogMetaphone by lazy { metaphoneEncoder.encode(recogWord) }

        return when (difficulty) {
            DIFF_HARD -> {
                // Exact match required.
                storyWordInfo.normalizedText == recogWord
            }
            DIFF_MEDIUM -> {
                val levDistance = levenshteinProcessor.distance(storyWordInfo.normalizedText, recogWord, 2)
                val textMatch = levDistance <= 1
                // Fallback to pronunciation match, ensuring metaphone codes are valid before comparing.
                val phoneticFallbackMatch = storyWordInfo.metaphoneCode.isNotBlank() &&
                                          storyWordInfo.metaphoneCode == recogMetaphone &&
                                          levDistance <= 2
                textMatch || phoneticFallbackMatch
            }
            DIFF_EASY -> {
                // Always forgive common stop words, or accept a matching pronunciation.
                STOP_WORDS_STRICT.contains(storyWordInfo.normalizedText) ||
                (storyWordInfo.metaphoneCode.isNotBlank() && storyWordInfo.metaphoneCode == recogMetaphone)
            }
            else -> false
        }
    }


    private fun normalizedSimilarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        val dist = levenshteinProcessor.distance(a, b, maxLen) // Use the injected processor
        val sim = 1.0f - (dist.toFloat() / maxLen.toFloat())
        return sim.coerceIn(0f, 1f)
    }

    private fun getLockingParams(difficulty: Int): LockingParams {
        return when (difficulty) {
            DIFF_EASY -> LockingParams(highConfidenceRatio = 0.85f, requiredConsecutiveMatches = 2)
            DIFF_MEDIUM -> LockingParams(highConfidenceRatio = 0.92f, requiredConsecutiveMatches = 3)
            DIFF_HARD -> LockingParams(highConfidenceRatio = 0.98f, requiredConsecutiveMatches = 2)
            else -> LockingParams(0.90f, 2) // Default case
        }
    }

    private fun normalizeToken(s: String): String = s.lowercase().replace(Regex("[^a-z0-9']"), "")

    companion object {
        private const val DIFF_EASY = 0
        private const val DIFF_MEDIUM = 1
        private const val DIFF_HARD = 2
        private val STOP_WORDS_STRICT = setOf("a", "an", "the", "to", "of", "in", "on", "at")
        private const val REALTIME_LOOKAHEAD = 0
        private const val ENABLE_CONSECUTIVE_DEBUG = true
    }
}
