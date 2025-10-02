package com.example.realtalkenglishwithAI.utils

import com.example.realtalkenglishwithAI.fragment.StoryReadingFragment
import org.apache.commons.codec.language.DoubleMetaphone

// --- Stop Words (Shrunk to only include non-learning filler words) --- //
private val beginnerStopWords = setOf("um", "uh", "yeah")
private val intermediateStopWords = setOf("um", "uh", "yeah")

/**
 * Defines a common interface for all word matching strategies.
 * It now receives the full WordInfo and RecognizedInfo objects to use cached data.
 */
interface MatchingStrategy {
    fun isMatch(wordInfo: StoryReadingFragment.WordInfo, recognizedInfo: StoryReadingFragment.RecognizedInfo): Boolean
}

/**
 * A factory object to get the appropriate matching strategy based on the difficulty level.
 */
object WordMatcherFactory {
    // Strategies are now stateless and can be singletons
    private val beginner = BeginnerStrategy()
    private val intermediate = IntermediateStrategy()
    private val advanced = AdvancedStrategy()

    fun getStrategy(difficultyLevel: Int): MatchingStrategy {
        return when (difficultyLevel) {
            0 -> beginner
            1 -> intermediate
            else -> advanced
        }
    }
}

/**
 * Strategy for Beginner level.
 * Uses pre-calculated metaphone codes for maximum performance.
 */
private class BeginnerStrategy : MatchingStrategy {
    override fun isMatch(wordInfo: StoryReadingFragment.WordInfo, recognizedInfo: StoryReadingFragment.RecognizedInfo): Boolean {
        val isCapitalized = wordInfo.originalText.first().isUpperCase()
        val isFirstWordInSentence = wordInfo.wordIndexInSentence == 0
        val isProperNoun = isCapitalized && !isFirstWordInSentence

        if (isProperNoun || beginnerStopWords.contains(wordInfo.normalizedText)) return true

        // Directly compare the pre-calculated metaphone codes. No new calculation needed for the story word.
        return wordInfo.metaphoneCode == recognizedInfo.metaphone
    }
}

/**
 * Strategy for Intermediate level.
 * Uses a dynamic Levenshtein distance threshold based on word length.
 */
private class IntermediateStrategy : MatchingStrategy {
    private val levenshtein = LevenshteinProcessor()

    override fun isMatch(wordInfo: StoryReadingFragment.WordInfo, recognizedInfo: StoryReadingFragment.RecognizedInfo): Boolean {
        val isCapitalized = wordInfo.originalText.first().isUpperCase()
        val isFirstWordInSentence = wordInfo.wordIndexInSentence == 0
        val isProperNoun = isCapitalized && !isFirstWordInSentence

        if (isProperNoun || intermediateStopWords.contains(wordInfo.normalizedText)) return true

        val len = wordInfo.normalizedText.length
        val threshold = when {
            len <= 3 -> 0       // Strict for very short words
            len in 4..7 -> 1  // Allow 1 error for medium words
            else -> {
                // For long words, allow a percentage-based error, but capped for fairness.
                // 20% of the length, with a minimum of 2 and a maximum of 3 errors.
                (len * 0.2).toInt().coerceAtLeast(2).coerceAtMost(3)
            }
        }

        val distance = levenshtein.distance(wordInfo.normalizedText, recognizedInfo.normalized, threshold)
        return distance <= threshold
    }
}

/**
 * Strategy for Advanced level.
 * Requires an exact string match after a second, stricter normalization pass to be fair.
 */
private class AdvancedStrategy : MatchingStrategy {
    override fun isMatch(wordInfo: StoryReadingFragment.WordInfo, recognizedInfo: StoryReadingFragment.RecognizedInfo): Boolean {
        // Apply a stricter, local normalization to handle cases like "it's" vs "its" fairly.
        val normStory = normalizeForAdvanced(wordInfo.normalizedText)
        val normRecog = normalizeForAdvanced(recognizedInfo.normalized)
        return normStory == normRecog
    }

    /**
     * A stricter normalization specific to the Advanced strategy.
     * It removes all apostrophes and any remaining non-alphanumeric characters.
     */
    private fun normalizeForAdvanced(s: String): String {
        // The input 's' is already lowercased from the global normalization.
        return s.replace(Regex("[’'`]"), "")   // Removes various apostrophe types
            .replace(Regex("[^a-z0-9]"), "") // Removes any other non-alphanumeric characters
    }
}
