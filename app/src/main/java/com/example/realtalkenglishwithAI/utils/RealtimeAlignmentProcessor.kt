package com.example.realtalkenglishwithAI.utils

import android.util.Log
import com.example.realtalkenglishwithAI.fragment.StoryReadingFragment
import com.example.realtalkenglishwithAI.utils.WordMatchingUtils
import kotlin.math.min

class RealtimeAlignmentProcessor(private val levenshteinProcessor: LevenshteinProcessor) {

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

            val matchScore = WordMatchingUtils.getMatchScore(recogWord, storyWordInfo.normalizedText)
            val isMatch = isWordMatch(matchScore, difficultyLevel)

            if (isMatch) {
                if (storyWordInfo.status != StoryReadingFragment.WordMatchStatus.CORRECT) {
                    storyWordInfo.status = StoryReadingFragment.WordMatchStatus.CORRECT
                    dirtySentences.add(storyWordInfo.sentenceIndex)
                }

                if (ENABLE_CONSECUTIVE_DEBUG) {
                    Log.d("ConsecDebug", "storyIdx=$storyIndex recWord='$recogWord' sim=${String.format("%.2f", matchScore)} pending=$pendingLockIndex count=$pendingConsecutiveCount")
                }

                if (matchScore >= params.highConfidenceRatio) {
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
                if (pendingLockIndex != -1) {
                    if (ENABLE_CONSECUTIVE_DEBUG) Log.d("ConsecDebug", "RESET pending lock due to mismatch.")
                    resetPendingLock()
                }
                break
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

    private fun isWordMatch(score: Float, difficulty: Int): Boolean {
        val threshold = when (difficulty) {
            DIFF_EASY -> 0.5f
            DIFF_MEDIUM -> 0.65f
            DIFF_HARD -> 0.8f
            else -> 0.6f
        }
        return score >= threshold
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
        private const val ENABLE_CONSECUTIVE_DEBUG = true
    }
}
