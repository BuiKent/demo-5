package com.example.realtalkenglishwithAI.utils

import org.apache.commons.codec.language.DoubleMetaphone
import kotlin.math.max

object WordMatchingUtils {

    private val levenshtein = LevenshteinProcessor()
    private val metaphoneEncoder = DoubleMetaphone()

    /**
     * A general-purpose matching score function.
     * It returns a score between 0.0 and 1.0.
     */
    fun getMatchScore(recognized: String, target: String): Float {
        if (recognized.isBlank() || target.isBlank()) return 0.0f

        val normalizedRec = normalize(recognized)
        val normalizedTarget = normalize(target)

        if (normalizedRec == normalizedTarget) return 1.0f

        // Levenshtein distance score
        val levDistance = levenshtein.distance(normalizedRec, normalizedTarget, normalizedTarget.length)
        val length = max(normalizedRec.length, normalizedTarget.length)
        val levScore = if (length > 0) (length - levDistance).toFloat() / length else 0.0f

        // Metaphone score
        val recMetaphone = metaphoneEncoder.encode(normalizedRec)
        val targetMetaphone = metaphoneEncoder.encode(normalizedTarget)
        val metaphoneScore = if (recMetaphone.isNotBlank() && recMetaphone == targetMetaphone) 1.0f else 0.0f
        
        // Combine scores: give more weight to Levenshtein, but boost if Metaphone matches.
        var finalScore = levScore
        if (metaphoneScore > 0) {
            finalScore = (finalScore + metaphoneScore) / 2.0f * 1.1f // Boost if both are good
        }
        
        return finalScore.coerceIn(0.0f, 1.0f)
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9']"), "")
}