package com.example.realtalkenglishwithAI.utils

import android.util.Log
import com.example.realtalkenglishwithAI.fragment.StoryReadingFragment
import org.apache.commons.codec.language.DoubleMetaphone
import kotlin.math.abs

// --- Top-level constants for scoring ---
private const val ALIGNMENT_BASE_MATCH_SCORE = 5
private const val ALIGNMENT_MISMATCH_PENALTY = -3
private const val ALIGNMENT_GAP_PENALTY = -2

// --- Parameterized Architecture Components ---

enum class AlignmentLevel { WORD, SENTENCE }

data class AlignmentParams(
    val maxJumpDistance: Int,
    val highConfidenceRatio: Float,
    val distancePenaltyFactor: Float
)

fun getAlignmentParams(difficultyLevel: Int, level: AlignmentLevel): AlignmentParams {
    return when (difficultyLevel) {
        0 -> { // Beginner
            val base = AlignmentParams(maxJumpDistance = 5, highConfidenceRatio = 0.90f, distancePenaltyFactor = 0.08f)
            if (level == AlignmentLevel.WORD) base.copy(maxJumpDistance = (base.maxJumpDistance * 0.6).toInt().coerceAtLeast(1)) else base
        }
        1 -> { // Intermediate
            val base = AlignmentParams(maxJumpDistance = 3, highConfidenceRatio = 0.95f, distancePenaltyFactor = 0.1f)
            if (level == AlignmentLevel.WORD) base.copy(maxJumpDistance = (base.maxJumpDistance * 0.5).toInt().coerceAtLeast(1)) else base
        }
        else -> { // Advanced
            val base = AlignmentParams(maxJumpDistance = 1, highConfidenceRatio = 0.99f, distancePenaltyFactor = 0.12f)
            if (level == AlignmentLevel.WORD) base.copy(maxJumpDistance = (base.maxJumpDistance * 0.4).toInt().coerceAtLeast(1)) else base
        }
    }
}

// --- Alignment Data Structures (Kept for compatibility) ---

enum class AlignmentMove {
    MATCH, SUBSTITUTION, DELETION, INSERTION
}

data class AlignmentResult(
    val moves: List<AlignmentMove>,
    val alignedStoryWords: List<StoryReadingFragment.WordInfo?>,
    val alignedRecognizedWords: List<StoryReadingFragment.RecognizedInfo?>,
    val totalScore: Int
)

class SequenceAligner {

    private val levenshteinProcessor = LevenshteinProcessor() // Internal instance

    private fun isWordMatch(storyWordInfo: StoryReadingFragment.WordInfo, recogWordInfo: StoryReadingFragment.RecognizedInfo, difficulty: Int): Boolean {
        val recogMetaphone = recogWordInfo.metaphone

        return when (difficulty) {
            0 -> { // Beginner
                val storyMetaphone = storyWordInfo.metaphoneCode
                storyMetaphone.isNotBlank() && storyMetaphone == recogMetaphone
            }
            1 -> { // Intermediate
                val levDistance = levenshteinProcessor.distance(storyWordInfo.normalizedText, recogWordInfo.normalized, 2)
                val textMatch = levDistance <= 1
                val phoneticFallbackMatch = storyWordInfo.metaphoneCode.isNotBlank() &&
                                          storyWordInfo.metaphoneCode == recogMetaphone &&
                                          levDistance <= 2
                textMatch || phoneticFallbackMatch
            }
            else -> { // Advanced
                storyWordInfo.normalizedText == recogWordInfo.normalized
            }
        }
    }

    fun alignWindow(
        storyWordsWindow: List<StoryReadingFragment.WordInfo>,
        recognizedWords: List<StoryReadingFragment.RecognizedInfo>,
        difficultyLevel: Int,
        params: AlignmentParams
    ): AlignmentResult {
        val m = storyWordsWindow.size
        val n = recognizedWords.size

        if (m == 0 || n == 0) {
            return AlignmentResult(emptyList(), emptyList(), emptyList(), 0)
        }

        val dp = Array(m + 1) { FloatArray(n + 1) { 0f } }
        val trace = Array(m + 1) { Array<AlignmentMove>(n + 1) { AlignmentMove.DELETION } }

        var maxScore = 0f
        var maxI = 0
        var maxJ = 0

        for (i in 1..m) {
            for (j in 1..n) {
                val storyWord = storyWordsWindow[i - 1]
                val recognizedWord = recognizedWords[j - 1]

                val distanceFromExpected = i - 1 // How far into the window this story word is
                
                val isMatch = isWordMatch(storyWord, recognizedWord, difficultyLevel)

                var substitutionScore = if(isMatch) ALIGNMENT_BASE_MATCH_SCORE.toFloat() else ALIGNMENT_MISMATCH_PENALTY.toFloat()

                if (isMatch) {
                    val distance = distanceFromExpected.toFloat()
                    val confidenceRatio = substitutionScore / ALIGNMENT_BASE_MATCH_SCORE

                    // Hard Cap: If match is too far and not confident, reject it entirely.
                    if (distanceFromExpected > params.maxJumpDistance && confidenceRatio < params.highConfidenceRatio) {
                        substitutionScore = Float.NEGATIVE_INFINITY // Reject the match
                    } else {
                        // Quadratic Penalty: Apply a penalty that grows with the square of the distance.
                        val quadraticPenalty = params.distancePenaltyFactor * distance * distance
                        substitutionScore -= quadraticPenalty
                    }
                }

                val scoreFromSubstitution = dp[i - 1][j - 1] + substitutionScore
                val scoreFromDeletion = dp[i - 1][j] + ALIGNMENT_GAP_PENALTY
                val scoreFromInsertion = dp[i][j - 1] + ALIGNMENT_GAP_PENALTY

                // Prevent score from going below a reasonable floor to avoid large negative influence
                val bestScore = maxOf(scoreFromSubstitution, scoreFromDeletion, scoreFromInsertion).coerceAtLeast(ALIGNMENT_MISMATCH_PENALTY * 10f)
                
                if (bestScore > 0 || dp[i][j] != 0f) { // Optimization to start path from first potential match
                     dp[i][j] = bestScore
                } else {
                     dp[i][j] = 0f
                }

                when (bestScore) {
                    scoreFromSubstitution -> trace[i][j] = if (isMatch) AlignmentMove.MATCH else AlignmentMove.SUBSTITUTION
                    scoreFromDeletion -> trace[i][j] = AlignmentMove.DELETION
                    scoreFromInsertion -> trace[i][j] = AlignmentMove.INSERTION
                }

                if (bestScore > maxScore) {
                    maxScore = bestScore
                    maxI = i
                    maxJ = j
                }
            }
        }

        val moves = mutableListOf<AlignmentMove>()
        val alignedStory = mutableListOf<StoryReadingFragment.WordInfo?>()
        val alignedRecog = mutableListOf<StoryReadingFragment.RecognizedInfo?>()

        var i = maxI
        var j = maxJ

        while (i > 0 || j > 0) {
            if (dp[i][j] <= ALIGNMENT_MISMATCH_PENALTY * 10f && (i != maxI || j != maxJ)) {
                // Stop if score is at the floor, but not on the very first cell we start from.
                break
            }

            val move = when {
                i > 0 && j > 0 -> trace[i][j]
                i > 0 -> AlignmentMove.DELETION
                j > 0 -> AlignmentMove.INSERTION
                else -> break // Should not be reached
            }

            moves.add(0, move)
            when (move) {
                AlignmentMove.MATCH, AlignmentMove.SUBSTITUTION -> {
                    alignedStory.add(0, storyWordsWindow.getOrNull(i - 1))
                    alignedRecog.add(0, recognizedWords.getOrNull(j - 1))
                    i--
                    j--
                }
                AlignmentMove.DELETION -> {
                    alignedStory.add(0, storyWordsWindow.getOrNull(i - 1))
                    alignedRecog.add(0, null)
                    i--
                }
                AlignmentMove.INSERTION -> {
                    alignedStory.add(0, null)
                    alignedRecog.add(0, recognizedWords.getOrNull(j - 1))
                    j--
                }
            }
        }
        return AlignmentResult(moves, alignedStory, alignedRecog, maxScore.toInt())
    }
}
