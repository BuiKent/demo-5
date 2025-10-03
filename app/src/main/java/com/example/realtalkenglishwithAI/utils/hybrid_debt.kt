package com.example.realtalkenglishwithAI.utils

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

// --- Centralized Enums and Models for the new architecture ---

enum class Mode { BEGINNER, MEDIUM, ADVANCED }

enum class WordState { PENDING, CORRECT, INCORRECT, UNRESOLVED_DEBT }

data class RecognizedToken(val text: String, val confidence: Float = 1.0f)

// --- UI Updater Interface: To be implemented by the Fragment --- 
interface DebtUI {
    enum class Color { GREEN, RED, SUBTLE_DEBT, DEFAULT }

    fun markWord(index: Int, color: Color, locked: Boolean)
    fun showDebtMarker(index: Int)
    fun hideDebtMarker(index: Int)
    fun onAdvanceCursorTo(index: Int)
    fun logMetric(key: String, value: Any)
}

/**
 * Core logic for the hybrid debt system. Manages word states, handles incoming tokens,
 * and coordinates with the UI, a cheap background collector, and a strict correction manager.
 */
class Phase0Manager(
    val storyWords: List<String>,
    val ui: DebtUI,
    private val mode: Mode = Mode.MEDIUM
) {
    private val n = storyWords.size
    val wordStates = MutableList(n) { WordState.PENDING }
    private var cursorIndex = 0
    private var unreadDebtIndex: Int? = null
    private val correctionBuffers = mutableMapOf<Int, ArrayDeque<RecognizedToken>>()

    // --- Configurable parameters ---
    private val MAX_LOOKAHEAD = 5
    private val thresholds = mapOf(
        Mode.BEGINNER to 0.60f,
        Mode.MEDIUM to 0.70f,
        Mode.ADVANCED to 0.82f
    )
    private val strictThresholds = mapOf(
        Mode.BEGINNER to 0.75f,
        Mode.MEDIUM to 0.85f,
        Mode.ADVANCED to 0.92f
    )

    // --- Child Workers --- 
    var collector: CheapBackgroundCollector? = null
    var strictManager: StrictCorrectionManager? = null

    @Synchronized
    fun onRecognizedWords(tokens: List<RecognizedToken>) {
        if (tokens.isEmpty()) return
        ui.logMetric("phase0_tokens_in", tokens.size)
        for (tk in tokens) {
            processToken(tk)
        }
    }

    private fun processToken(token: RecognizedToken) {
        advanceCursorPastLocked()
        if (cursorIndex >= n) return

        val debtIndex = unreadDebtIndex
        if (debtIndex == null) {
            // Normal reading mode
            val target = storyWords[cursorIndex]
            if (isFastMatch(token.text, target)) {
                markCorrect(cursorIndex)
            } else {
                // Create a new debt
                wordStates[cursorIndex] = WordState.UNRESOLVED_DEBT
                unreadDebtIndex = cursorIndex
                correctionBuffers[cursorIndex] = ArrayDeque()
                ui.showDebtMarker(cursorIndex)
                ui.logMetric("debt_created", cursorIndex)

                tryMatchToFollowing(token)
                appendToCorrectionBuffer(cursorIndex, token)
            }
        } else {
            // A debt is currently pending
            appendToCorrectionBuffer(debtIndex, token)
            collector?.onDebtBufferUpdated(debtIndex, correctionBuffers[debtIndex]!!.toList())

            tryMatchToFollowing(token)

            if (correctionBuffers[debtIndex]!!.size >= MAX_LOOKAHEAD) {
                finalizeDebtAsIncorrect(debtIndex)
            }
        }
    }

    @Synchronized
    fun requestStrictReeval(debtIndex: Int, candidateToken: RecognizedToken) {
        if (wordStates.getOrNull(debtIndex) != WordState.UNRESOLVED_DEBT) return

        val strictMatcher = { a: String, b: String -> WordMatchingUtils.getMatchScore(a, b) }
        val threshold = strictThresholds[mode]!!

        strictManager?.submitStrictCheck(debtIndex, candidateToken.text, storyWords[debtIndex], strictMatcher, threshold) { result ->
            // This callback runs on a background thread. 
            // We need to re-synchronize with the Phase0Manager to modify its state.
            handleStrictResult(result)
        }
    }

    @Synchronized
    private fun handleStrictResult(result: StrictResult) {
        ui.logMetric("strict_result", "index=${result.debtIndex}, verdict=${result.verdict}, time=${result.timeMs}ms")
        
        // Ensure the state hasn't changed while the strict check was running
        if (wordStates.getOrNull(result.debtIndex) != WordState.UNRESOLVED_DEBT) return

        when (result.verdict) {
            Verdict.GREEN -> {
                markCorrect(result.debtIndex)
                correctionBuffers.remove(result.debtIndex)
                if (unreadDebtIndex == result.debtIndex) unreadDebtIndex = null
                advanceCursorPastLocked()
            }
            Verdict.RED -> {
                finalizeDebtAsIncorrect(result.debtIndex)
            }
            Verdict.UNKNOWN -> {
                // The check was inconclusive or timed out. For now, we do nothing and wait
                // for more tokens or for the debt to expire naturally.
                ui.logMetric("strict_unknown", result.debtIndex)
            }
        }
    }

    @Synchronized
    fun finalizeDebtAsIncorrect(debtIndex: Int) {
        if (wordStates.getOrNull(debtIndex) == WordState.UNRESOLVED_DEBT) {
            wordStates[debtIndex] = WordState.INCORRECT
            ui.markWord(debtIndex, DebtUI.Color.RED, locked = true)
            ui.hideDebtMarker(debtIndex)
            correctionBuffers.remove(debtIndex)
            ui.logMetric("debt_finalized_incorrect", debtIndex)

            if (unreadDebtIndex == debtIndex) unreadDebtIndex = null
            advanceCursorPastLocked()
        }
    }

    private fun markCorrect(index: Int) {
        if (wordStates.getOrNull(index) == WordState.CORRECT) return
        wordStates[index] = WordState.CORRECT
        ui.markWord(index, DebtUI.Color.GREEN, locked = true)
        ui.hideDebtMarker(index) 
        ui.logMetric("mark_correct", index)

        if (unreadDebtIndex == index) unreadDebtIndex = null
        if (index == cursorIndex) {
            advanceCursorPastLocked()
        }
    }

    private fun tryMatchToFollowing(token: RecognizedToken) {
        val limit = min(n - 1, cursorIndex + MAX_LOOKAHEAD)
        for (idx in (cursorIndex + 1)..limit) {
            if (wordStates[idx] == WordState.PENDING) {
                if (isFastMatch(token.text, storyWords[idx])) {
                    markCorrect(idx)
                    break
                }
            }
        }
        advanceCursorPastLocked()
    }

    private fun appendToCorrectionBuffer(debtIndex: Int, token: RecognizedToken) {
        val buf = correctionBuffers.getOrPut(debtIndex) { ArrayDeque() }
        if (buf.size >= MAX_LOOKAHEAD) buf.removeFirst()
        buf.addLast(token)
    }

    private fun advanceCursorPastLocked() {
        while (cursorIndex < n && wordStates[cursorIndex] == WordState.CORRECT) {
            cursorIndex++
        }
        ui.onAdvanceCursorTo(cursorIndex)
    }

    private fun isFastMatch(a: String, b: String): Boolean {
        val score = WordMatchingUtils.getMatchScore(a, b)
        return score >= thresholds[mode]!!
    }

    @Synchronized
    fun reset() {
        wordStates.fill(WordState.PENDING)
        cursorIndex = 0
        unreadDebtIndex = null
        correctionBuffers.clear()
        collector?.shutdown()
        strictManager?.shutdown()
    }
}

/**
 * Runs on a background thread to cheaply inspect debt buffers without blocking the UI.
 */
class CheapBackgroundCollector(
    private val manager: Phase0Manager,
    private val mode: Mode = Mode.MEDIUM
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Debt-Collector-Thread").apply { isDaemon = true }
    }
    private val quickThreshold = 0.55f // A cheap heuristic threshold

    fun onDebtBufferUpdated(debtIndex: Int, bufferSnapshot: List<RecognizedToken>) {
        executor.execute {
            try {
                val target = manager.storyWords.getOrNull(debtIndex) ?: return@execute
                for (tk in bufferSnapshot.asReversed()) { 
                    val quickScore = WordMatchingUtils.getMatchScore(tk.text, target)
                    if (quickScore >= quickThreshold && tk.confidence >= 0.2f) {
                        manager.requestStrictReeval(debtIndex, tk)
                        return@execute
                    }
                }
            } catch (t: Throwable) {
                manager.ui.logMetric("collector_exception", t.message ?: "unknown")
            }
        }
    }

    fun shutdown() {
        try {
            executor.shutdownNow()
            executor.awaitTermination(200, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
