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
 * Core logic for Phase 0. Manages word states, handles incoming tokens,
 * and coordinates with the UI and a background collector.
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
    
    // Configurable parameters
    private val MAX_LOOKAHEAD = 5
    private val thresholds = mapOf(
        Mode.BEGINNER to 0.60f,
        Mode.MEDIUM to 0.70f,
        Mode.ADVANCED to 0.82f
    )

    // The collector is set after initialization to break the circular dependency.
    var collector: CheapBackgroundCollector? = null

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
                // Create a new debt, but don't stop.
                unreadDebtIndex = cursorIndex
                correctionBuffers[cursorIndex] = ArrayDeque()
                ui.showDebtMarker(cursorIndex)
                ui.logMetric("debt_created", cursorIndex)

                // Try to match this token to the words immediately following the new debt.
                tryMatchToFollowing(token)
                appendToCorrectionBuffer(cursorIndex, token)
            }
        } else {
            // A debt is currently pending at 'debtIndex'
            appendToCorrectionBuffer(debtIndex, token)
            collector?.onDebtBufferUpdated(debtIndex, correctionBuffers[debtIndex]!!.toList())
            
            // While waiting for the debt to be resolved, still try to match new tokens to upcoming words.
            tryMatchToFollowing(token)
            
            // If we've heard too many words after the debt, give up on it.
            if (correctionBuffers[debtIndex]!!.size >= MAX_LOOKAHEAD) {
                finalizeDebtAsIncorrect(debtIndex)
            }
        }
    }

    private fun tryMatchToFollowing(token: RecognizedToken) {
        val limit = min(n - 1, cursorIndex + MAX_LOOKAHEAD)
        // Start looking from the word *after* the current cursor position
        for (idx in (cursorIndex + 1)..limit) {
            if (wordStates[idx] == WordState.PENDING) {
                if (isFastMatch(token.text, storyWords[idx])) {
                    markCorrect(idx)
                    // If we colored a word, we can stop trying to use this token.
                    // This prevents one spoken word from coloring multiple story words.
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

    @Synchronized
    fun requestStrictReeval(debtIndex: Int, candidateToken: RecognizedToken) {
        if (wordStates[debtIndex] != WordState.PENDING && wordStates[debtIndex] != WordState.UNRESOLVED_DEBT) return

        if (isStrictMatch(candidateToken.text, storyWords[debtIndex])) {
            markCorrect(debtIndex)
            ui.hideDebtMarker(debtIndex)
            ui.logMetric("debt_resolved_by_collector", debtIndex)
            // Clear the buffer for the resolved debt
            correctionBuffers.remove(debtIndex)
            // If this was the active debt, clear it.
            if (unreadDebtIndex == debtIndex) unreadDebtIndex = null
            
            // Crucially, advance the cursor past any newly resolved words.
            advanceCursorPastLocked()
        } else {
            ui.logMetric("collector_strict_failed", debtIndex)
        }
    }

    @Synchronized
    fun finalizeDebtAsIncorrect(debtIndex: Int) {
        if (wordStates[debtIndex] == WordState.PENDING || wordStates[debtIndex] == WordState.UNRESOLVED_DEBT) {
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
        wordStates[index] = WordState.CORRECT
        ui.markWord(index, DebtUI.Color.GREEN, locked = true)
        ui.hideDebtMarker(index) // Hide marker in case it was a debt
        ui.logMetric("mark_correct", index)

        if (unreadDebtIndex == index) unreadDebtIndex = null
        if (index == cursorIndex) {
            advanceCursorPastLocked()
        }
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

    private fun isStrictMatch(a: String, b: String): Boolean {
        // For strict matching, we might use a higher threshold or a different algorithm.
        // For now, we use the same as fast match but this can be evolved.
        val score = WordMatchingUtils.getMatchScore(a, b)
        return score >= (thresholds[mode]!! + 0.1f).coerceAtMost(1.0f) // e.g., slightly stricter
    }

    @Synchronized
    fun reset() {
        wordStates.fill(WordState.PENDING)
        cursorIndex = 0
        unreadDebtIndex = null
        correctionBuffers.clear()
        collector?.shutdown()
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
                // Look for a promising candidate in the buffer
                for (tk in bufferSnapshot.asReversed()) { // Check most recent first
                    val quickScore = WordMatchingUtils.getMatchScore(tk.text, target)
                    if (quickScore >= quickThreshold && tk.confidence >= 0.2f) {
                        // Candidate found -> suggest strict re-evaluation to the manager
                        manager.requestStrictReeval(debtIndex, tk)
                        return@execute // Stop after finding one candidate
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