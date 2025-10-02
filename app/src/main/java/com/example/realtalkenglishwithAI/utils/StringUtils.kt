package com.example.realtalkenglishwithAI.utils

import kotlin.math.abs
import kotlin.math.min

/**
 * A processor for calculating Levenshtein distance with performance optimizations.
 * It uses a cache (memoization) to avoid re-computing distances for the same pair of strings
 * and an early exit strategy to stop calculations when a given threshold is surpassed.
 */
class LevenshteinProcessor {

    private val cache = mutableMapOf<Pair<String, String>, Int>()

    /**
     * Calculates the Levenshtein distance between two strings, using the cache and a threshold.
     *
     * @param s1 The first string.
     * @param s2 The second string.
     * @param threshold The maximum acceptable distance. If the actual distance is greater than this,
     *                  the function may return a value larger than the threshold without computing the exact distance.
     * @return The Levenshtein distance, or a value > threshold if the distance is too large.
     */
    fun distance(s1: String, s2: String, threshold: Int): Int {
        val key = if (s1.length < s2.length) s1 to s2 else s2 to s1
        return cache.getOrPut(key) {
            levenshteinWithThreshold(key.first, key.second, threshold)
        }
    }

    /**
     * An optimized implementation of the Levenshtein distance algorithm that exits early
     * if the calculated distance will exceed the specified threshold.
     * It also uses only two rows of the DP matrix to save space.
     */
    private fun levenshteinWithThreshold(s1: String, s2: String, threshold: Int): Int {
        val n = s1.length
        val m = s2.length

        // Early exit if the length difference is already greater than the threshold
        if (abs(n - m) > threshold) {
            return threshold + 1
        }

        var v0 = IntArray(m + 1) { it }
        var v1 = IntArray(m + 1)

        for (i in 0 until n) {
            v1[0] = i + 1
            var minRowValue = v1[0]

            for (j in 0 until m) {
                val cost = if (s1[i] == s2[j]) 0 else 1
                v1[j + 1] = minOf(v1[j] + 1, v0[j + 1] + 1, v0[j] + cost)
                minRowValue = min(minRowValue, v1[j + 1])
            }

            // Early exit if the minimum value in the current row exceeds the threshold
            if (minRowValue > threshold) {
                return threshold + 1
            }

            v0 = v1.clone()
        }

        return v1[m]
    }
}