package com.example.realtalkenglishwithAI.utils

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

enum class Verdict { GREEN, RED, UNKNOWN }
data class StrictResult(val debtIndex: Int, val verdict: Verdict, val score: Float, val timeMs: Long)

/**
 * Manages heavy, background correction tasks.
 * It uses the API design from the user, combined with a safer timeout mechanism.
 *
 * @param maxConcurrency The number of concurrent heavy tasks allowed.
 * @param perTaskTimeoutMs The maximum time allowed for a single task.
 */
class StrictCorrectionManager(
    private val maxConcurrency: Int = 1,
    private val perTaskTimeoutMs: Long = 600L
) {
    private val executor = Executors.newFixedThreadPool(maxConcurrency) { r ->
        Thread(r, "StrictCorrectionThread").apply { isDaemon = true }
    }

    /**
     * Submits a heavy, time-limited strict check.
     * The callback is invoked on a background thread. It is the callback's responsibility
     * to dispatch any UI updates to the main thread.
     *
     * @param debtIndex The index of the debt.
     * @param candidateText The recognized word being checked.
     * @param targetText The original story word.
     * @param strictMatcher A user-supplied lambda that performs the heavy comparison, returning a score (0.0 to 1.0).
     * @param threshold The score threshold to be considered a GREEN verdict.
     * @param callback The function to call with the result.
     */
    fun submitStrictCheck(
        debtIndex: Int,
        candidateText: String,
        targetText: String,
        strictMatcher: (String, String) -> Float,
        threshold: Float,
        callback: (StrictResult) -> Unit
    ) {
        val heavyTask: Future<*> = executor.submit {
            try {
                val startTime = System.currentTimeMillis()
                val score = strictMatcher(candidateText, targetText)
                val timeMs = System.currentTimeMillis() - startTime

                // If the task took longer than the timeout, it might have already been cancelled.
                // This check prevents sending a result after a timeout has already been reported.
                if (Thread.currentThread().isInterrupted) {
                    return@submit
                }

                val verdict = when {
                    score >= threshold -> Verdict.GREEN
                    // Use a lower bound for a confident "RED". Otherwise, it's UNKNOWN.
                    score < (threshold * 0.5f) -> Verdict.RED
                    else -> Verdict.UNKNOWN
                }
                val result = StrictResult(debtIndex, verdict, score, timeMs)
                callback(result)

            } catch (e: Exception) {
                if (e !is InterruptedException) {
                    callback(StrictResult(debtIndex, Verdict.UNKNOWN, 0f, 0L))
                }
            }
        }

        // "Observer" task to enforce the timeout
        executor.submit {
            try {
                heavyTask.get(perTaskTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                heavyTask.cancel(true) // Interrupt the heavy task
                // Report the timeout.
                callback(StrictResult(debtIndex, Verdict.UNKNOWN, 0f, perTaskTimeoutMs))
            } catch (e: Exception) {
                // Task either finished, was cancelled, or failed. A result has likely already been sent.
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
