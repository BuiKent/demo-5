package com.example.realtalkenglishwithAI.utils

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.min

// --- Centralized Enums and Models for the new architecture ---

enum class Mode { Beginner, Intermediate, Advanced }

enum class WordState { PENDING, CORRECT, INCORRECT, SKIPPED, UNRESOLVED_DEBT }

data class RecognizedToken(val text: String, val confidence: Float = 1.0f)

// --- UI Updater Interface: To be implemented by the Fragment --- 
interface DebtUI {
    enum class Color { GREEN, RED, DEFAULT }

    fun markWord(index: Int, color: Color)
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
    private val mode: Mode = Mode.Intermediate
) {
    private val n = storyWords.size
    val wordStates = MutableList(n) { WordState.PENDING }
    private var cursorIndex = 0
    private var unreadDebtIndex: Int? = null
    private val correctionBuffers = mutableMapOf<Int, ArrayDeque<RecognizedToken>>()
    private var lastTranscribedWords: List<String> = emptyList()

    // --- Configurable parameters ---
    private val MAX_LOOKAHEAD = 3 // Reduced from 5
    private val MAX_CORRECTION_ATTEMPTS = 2
    private val LOOKAHEAD_MIN_SCORE = 0.75f
    private val LOOKAHEAD_MARGIN = 0.20f

    private val thresholds = mapOf(
        Mode.Beginner to 0.70f,      // Increased from 0.60f
        Mode.Intermediate to 0.75f, // Increased from 0.70f
        Mode.Advanced to 0.82f
    )
    private val strictThresholds = mapOf(
        Mode.Beginner to 0.80f,      // Increased from 0.75f
        Mode.Intermediate to 0.85f,
        Mode.Advanced to 0.92f
    )

    // --- Child Workers --- 
    var collector: CheapBackgroundCollector? = null
    var strictManager: StrictCorrectionManager? = null

    @Synchronized
    fun onRecognizedWords(tokens: List<RecognizedToken>) {
        if (tokens.isEmpty()) return
        ui.logMetric("phase0_tokens_in", tokens.size)
        val fullTranscript = tokens.joinToString(" ") { it.text }
        ui.logMetric("phase0_full_transcript", fullTranscript)

        when (mode) {
            Mode.Beginner, Mode.Intermediate -> processTranscriptSynchronously(fullTranscript)
            Mode.Advanced -> {
                for (tk in tokens) {
                    processTokenForAdvanced(tk)
                }
            }
        }
    }

    private fun processTranscriptSynchronously(fullTranscript: String) {
        val allTranscribedWords = fullTranscript.split(Regex("\\s+")).map { it.lowercase() }.filter { it.isNotBlank() }

        if (allTranscribedWords.size < lastTranscribedWords.size) {
            lastTranscribedWords = emptyList()
        }

        var overlap = 0
        if (lastTranscribedWords.isNotEmpty()) {
            val maxCheck = min(lastTranscribedWords.size, allTranscribedWords.size)
            for (k in maxCheck downTo 1) {
                if (lastTranscribedWords.takeLast(k) == allTranscribedWords.take(k)) {
                    overlap = k
                    break
                }
            }
        }
        
        val rawNewWords = allTranscribedWords.drop(overlap)

        val fillers = setOf("uh", "um", "oh", "ah", "mm", "hmm", "like", "youknow")
        val newTranscribedWords = mutableListOf<String>()
        var prev: String? = null
        for (word in rawNewWords) {
            if (word in fillers) continue
            if (word == prev) continue
            newTranscribedWords.add(word)
            prev = word
        }

        if (newTranscribedWords.isEmpty()) {
            lastTranscribedWords = allTranscribedWords
            return
        }

        lastTranscribedWords = allTranscribedWords
        
        var storyIndex = wordStates.indexOfFirst { it == WordState.PENDING || it == WordState.INCORRECT }
        if (storyIndex == -1) return

        var transcriptIndex = 0

        while (storyIndex < n && transcriptIndex < newTranscribedWords.size) {
            val storyWord = storyWords[storyIndex]
            val transcriptWord = newTranscribedWords[transcriptIndex]

            if (wordStates[storyIndex] == WordState.INCORRECT) {
                // --- Correction Mode --- //
                if (isFastMatch(transcriptWord, storyWord, isStrict = true)) {
                    wordStates[storyIndex] = WordState.CORRECT
                    ui.markWord(storyIndex, DebtUI.Color.GREEN)
                    ui.logMetric("phase0_correction_success", "idx=${storyIndex}, word='${storyWord}', by='${transcriptWord}'")
                    storyIndex++
                    transcriptIndex++
                    continue
                }

                val nextStoryIndex = storyIndex + 1
                if (nextStoryIndex < n && isFastMatch(transcriptWord, storyWords[nextStoryIndex], isStrict = false)) {
                    wordStates[storyIndex] = WordState.SKIPPED
                    ui.markWord(storyIndex, DebtUI.Color.RED)
                    ui.logMetric("phase0_skip_user_advanced", "skipped_idx=${storyIndex}, matched_idx=${nextStoryIndex}")
                    wordStates[nextStoryIndex] = WordState.CORRECT
                    ui.markWord(nextStoryIndex, DebtUI.Color.GREEN)
                    storyIndex += 2
                    transcriptIndex++
                    continue
                }

                val attempts = correctionBuffers.getOrPut(storyIndex) { ArrayDeque() }
                attempts.add(RecognizedToken(transcriptWord))
                ui.logMetric("phase0_correction_attempt", "idx=${storyIndex}, word='${storyWord}', attempt='${transcriptWord}', count=${attempts.size}")

                if (attempts.size >= MAX_CORRECTION_ATTEMPTS) {
                    wordStates[storyIndex] = WordState.SKIPPED
                    ui.markWord(storyIndex, DebtUI.Color.RED)
                    ui.logMetric("phase0_skip_max_attempts", "idx=${storyIndex}, word='${storyWord}', attempts=${attempts.size}")
                    storyIndex++ 
                }
                transcriptIndex++
                
            } else {
                // --- Normal Reading Mode --- //
                if (isFastMatch(transcriptWord, storyWord, isStrict = false)) {
                    wordStates[storyIndex] = WordState.CORRECT
                    ui.markWord(storyIndex, DebtUI.Color.GREEN)
                    storyIndex++
                    transcriptIndex++
                } else {
                    var foundAhead = false
                    val limit = min(storyIndex + MAX_LOOKAHEAD, n)
                    val currentScore = WordMatchingUtils.getMatchScore(transcriptWord, storyWord)

                    for (i in (storyIndex + 1) until limit) {
                        if (wordStates[i] == WordState.PENDING) {
                            val candidateWord = storyWords[i]
                            val candidateScore = WordMatchingUtils.getMatchScore(transcriptWord, candidateWord)
                            val candidateIsShort = candidateWord.length <= 2
                            
                            val acceptLookahead = candidateScore >= LOOKAHEAD_MIN_SCORE &&
                                                  (candidateScore - currentScore) >= LOOKAHEAD_MARGIN &&
                                                  (!candidateIsShort || candidateWord == transcriptWord)

                            ui.logMetric("phase0_lookahead_decision", "idx=${i}, transcript='${transcriptWord}', currentScore=${String.format("%.2f", currentScore)}, candidate='${candidateWord}', candidateScore=${String.format("%.2f", candidateScore)}, delta=${String.format("%.2f", candidateScore - currentScore)}, accepted=${acceptLookahead}")

                            if (acceptLookahead) {
                                for (j in storyIndex until i) {
                                    if (wordStates[j] == WordState.PENDING) {
                                        wordStates[j] = WordState.SKIPPED
                                        ui.markWord(j, DebtUI.Color.RED)
                                        ui.logMetric("phase0_skip_lookahead", "idx=${j}, reason='lookahead_to_idx_${i}'")
                                    }
                                }
                                wordStates[i] = WordState.CORRECT
                                ui.markWord(i, DebtUI.Color.GREEN)
                                storyIndex = i + 1
                                transcriptIndex++
                                foundAhead = true
                                break
                            }
                        }
                    }
                    if (!foundAhead) {
                        wordStates[storyIndex] = WordState.INCORRECT
                        correctionBuffers.getOrPut(storyIndex) { ArrayDeque() }
                        ui.markWord(storyIndex, DebtUI.Color.RED)
                        ui.logMetric("phase0_mark_incorrect", "idx=${storyIndex}, story='${storyWord}', transcript='${transcriptWord}'")
                        transcriptIndex++
                    }
                }
            }
        }
        val newCursor = wordStates.indexOfFirst { it == WordState.PENDING || it == WordState.INCORRECT }
        ui.onAdvanceCursorTo(if (newCursor != -1) newCursor else n)
    }

    private fun processTokenForAdvanced(token: RecognizedToken) {
        advanceCursorPastLocked()
        if (cursorIndex >= n) return

        val debtIndex = unreadDebtIndex
        if (debtIndex == null) {
            val target = storyWords[cursorIndex]
            if (isFastMatch(token.text, target, isStrict = false)) {
                markCorrect(cursorIndex)
            } else {
                wordStates[cursorIndex] = WordState.UNRESOLVED_DEBT
                unreadDebtIndex = cursorIndex
                correctionBuffers[cursorIndex] = ArrayDeque()
                ui.showDebtMarker(cursorIndex)
                ui.logMetric("debt_created", cursorIndex)

                tryMatchToFollowing(token)
                appendToCorrectionBuffer(cursorIndex, token)
            }
        } else {
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
            handleStrictResult(result)
        }
    }

    @Synchronized
    private fun handleStrictResult(result: StrictResult) {
        ui.logMetric("strict_result", "index=${result.debtIndex}, verdict=${result.verdict}, time=${result.timeMs}ms")
        if (wordStates.getOrNull(result.debtIndex) != WordState.UNRESOLVED_DEBT) return

        when (result.verdict) {
            Verdict.GREEN -> {
                markCorrect(result.debtIndex)
                correctionBuffers.remove(result.debtIndex)
                if (unreadDebtIndex == result.debtIndex) unreadDebtIndex = null
            }
            Verdict.RED -> finalizeDebtAsIncorrect(result.debtIndex)
            Verdict.UNKNOWN -> ui.logMetric("strict_unknown", result.debtIndex)
        }
        advanceCursorPastLocked()
    }

    @Synchronized
    fun finalizeDebtAsIncorrect(debtIndex: Int) {
        if (wordStates.getOrNull(debtIndex) == WordState.UNRESOLVED_DEBT) {
            wordStates[debtIndex] = WordState.INCORRECT
            ui.markWord(debtIndex, DebtUI.Color.RED)
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
        ui.markWord(index, DebtUI.Color.GREEN)
        ui.hideDebtMarker(index) 
        ui.logMetric("mark_correct", index)

        if (unreadDebtIndex == index) unreadDebtIndex = null
        if (index == cursorIndex) advanceCursorPastLocked()
    }

    private fun tryMatchToFollowing(token: RecognizedToken) {
        val limit = min(n - 1, cursorIndex + MAX_LOOKAHEAD)
        for (idx in (cursorIndex + 1)..limit) {
            if (wordStates[idx] == WordState.PENDING) {
                if (isFastMatch(token.text, storyWords[idx], isStrict = false)) {
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
        while (cursorIndex < n && (wordStates[cursorIndex] == WordState.CORRECT || wordStates[cursorIndex] == WordState.SKIPPED) ) {
            cursorIndex++
        }
        ui.onAdvanceCursorTo(cursorIndex)
    }

    private fun isFastMatch(a: String, b: String, isStrict: Boolean): Boolean {
        val score = WordMatchingUtils.getMatchScore(a, b)
        val threshold = if (isStrict) strictThresholds[mode]!! else thresholds[mode]!!
        val isMatch = score >= threshold
        ui.logMetric("phase0_match_attempt", "story='${b}', transcript='${a}', score=${String.format("%.2f", score)}, threshold=${String.format("%.2f", threshold)}, strict=${isStrict}, match=${isMatch}")
        return isMatch
    }

    @Synchronized
    fun reset() {
        wordStates.fill(WordState.PENDING)
        cursorIndex = 0
        unreadDebtIndex = null
        correctionBuffers.clear()
        lastTranscribedWords = emptyList()
        collector?.shutdown()
        strictManager?.shutdown()
    }
}

class CheapBackgroundCollector(
    private val manager: Phase0Manager,
    private val mode: Mode = Mode.Intermediate
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
