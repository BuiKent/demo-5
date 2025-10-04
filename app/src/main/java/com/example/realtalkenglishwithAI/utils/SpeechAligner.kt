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

class SpeechAligner(
    val storyWords: List<String>,
    val ui: DebtUI,
    private val mode: Mode = Mode.Intermediate
) {
    private val n = storyWords.size
    // Point A: Normalized story words for consistent matching
    private val storyWordsNorm: List<String> = storyWords.map { it.lowercase().replace(Regex("[\\p{P}\\p{S}]"), "") }
    val wordStates = MutableList(n) { WordState.PENDING }
    private var cursorIndex = 0
    private var unreadDebtIndex: Int? = null
    private val correctionBuffers = mutableMapOf<Int, ArrayDeque<RecognizedToken>>()
    private var lastTranscribedWords: List<String> = emptyList()

    // --- Configurable parameters ---
    private val MAX_LOOKAHEAD = 3
    private val MAX_CORRECTION_ATTEMPTS = 2
    private val LOOKAHEAD_MIN_SCORE = 0.75f
    private val LOOKAHEAD_MARGIN = 0.20f
    private val MIN_TOKEN_CONFIDENCE = 0.25f // Point C
    private var lastLookaheadAt = 0L // Point F
    private val LOOKAHEAD_COOLDOWN_MS = 300L // Point F

    private val thresholds = mapOf(
        Mode.Beginner to 0.70f,
        Mode.Intermediate to 0.75f,
        Mode.Advanced to 0.82f
    )
    private val strictThresholds = mapOf(
        Mode.Beginner to 0.80f,
        Mode.Intermediate to 0.85f,
        Mode.Advanced to 0.92f
    )

    var collector: CheapBackgroundCollector? = null
    var strictManager: StrictCorrectionManager? = null

    @Synchronized
    fun onRecognizedWords(tokens: List<RecognizedToken>) {
        if (tokens.isEmpty()) return
        ui.logMetric("tokens_in", tokens.size)

        // Point C: Filter tokens by confidence
        val filteredTokens = tokens.filter { it.confidence >= MIN_TOKEN_CONFIDENCE }
        val allTranscribedWords = filteredTokens.map { it.text.trim().lowercase() }.filter { it.isNotBlank() }
        val fullTranscript = allTranscribedWords.joinToString(" ")
        ui.logMetric("full_transcript", fullTranscript)

        when (mode) {
            Mode.Beginner, Mode.Intermediate -> processTranscriptSynchronously(allTranscribedWords)
            Mode.Advanced -> {
                for (tk in filteredTokens) {
                    processTokenForAdvanced(tk)
                }
            }
        }
    }

    private fun processTranscriptSynchronously(allTranscribedWords: List<String>) {
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

        val fillers = setOf("uh", "um", "oh", "ah", "mm", "hmm", "like", "youknow", "erm")
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
            val storyWordNorm = storyWordsNorm[storyIndex]
            val transcriptWord = newTranscribedWords[transcriptIndex]

            if (wordStates[storyIndex] == WordState.INCORRECT) {
                // --- Correction Mode --- //
                if (isFastMatch(transcriptWord, storyWordNorm, isStrict = true)) {
                    wordStates[storyIndex] = WordState.CORRECT
                    ui.markWord(storyIndex, DebtUI.Color.GREEN)
                    correctionBuffers.remove(storyIndex) // Point D
                    ui.logMetric("correction_success", "idx=${storyIndex}, word='${storyWords[storyIndex]}', by='${transcriptWord}'")
                    storyIndex++
                    transcriptIndex++
                    continue
                }

                val nextStoryIndex = storyIndex + 1
                if (nextStoryIndex < n && isFastMatch(transcriptWord, storyWordsNorm[nextStoryIndex], isStrict = false)) {
                    wordStates[storyIndex] = WordState.SKIPPED
                    ui.markWord(storyIndex, DebtUI.Color.RED)
                    correctionBuffers.remove(storyIndex) // Point D
                    ui.logMetric("skip_user_advanced", "skipped_idx=${storyIndex}, matched_idx=${nextStoryIndex}")

                    wordStates[nextStoryIndex] = WordState.CORRECT
                    ui.markWord(nextStoryIndex, DebtUI.Color.GREEN)
                    correctionBuffers.remove(nextStoryIndex) // Point D

                    storyIndex = nextStoryIndex + 1
                    transcriptIndex++
                    continue
                }

                val attempts = correctionBuffers.getOrPut(storyIndex) { ArrayDeque() }
                attempts.add(RecognizedToken(transcriptWord))
                ui.logMetric("correction_attempt", "idx=${storyIndex}, word='${storyWords[storyIndex]}', attempt='${transcriptWord}', count=${attempts.size}")

                if (attempts.size >= MAX_CORRECTION_ATTEMPTS) {
                    wordStates[storyIndex] = WordState.SKIPPED
                    ui.markWord(storyIndex, DebtUI.Color.RED)
                    correctionBuffers.remove(storyIndex) // Point D
                    ui.logMetric("skip_max_attempts", "idx=${storyIndex}, word='${storyWords[storyIndex]}', attempts=${attempts.size}")
                    storyIndex++
                }
                transcriptIndex++

            } else {
                // --- Normal Reading Mode --- //
                if (isFastMatch(transcriptWord, storyWordNorm, isStrict = false)) {
                    wordStates[storyIndex] = WordState.CORRECT
                    correctionBuffers.remove(storyIndex) // Point D
                    ui.markWord(storyIndex, DebtUI.Color.GREEN)
                    storyIndex++
                    transcriptIndex++
                } else {
                    var foundAhead = false
                    val limit = min(n - 1, storyIndex + MAX_LOOKAHEAD) // Point B: inclusive limit
                    val currentScore = WordMatchingUtils.getMatchScore(transcriptWord, storyWordNorm)

                    // Point F: Cooldown for lookahead
                    if (System.currentTimeMillis() - lastLookaheadAt >= LOOKAHEAD_COOLDOWN_MS) {
                        for (i in (storyIndex + 1)..limit) { // Point B: inclusive loop
                            if (wordStates[i] == WordState.PENDING) {
                                val candidateWordNorm = storyWordsNorm[i]
                                val candidateScore = WordMatchingUtils.getMatchScore(transcriptWord, candidateWordNorm)
                                val candidateIsShort = candidateWordNorm.length <= 2

                                val acceptLookahead = candidateScore >= LOOKAHEAD_MIN_SCORE &&
                                        (candidateScore - currentScore) >= LOOKAHEAD_MARGIN &&
                                        (!candidateIsShort || candidateWordNorm == transcriptWord)

                                ui.logMetric("lookahead_decision", "idx=${i}, transcript='${transcriptWord}', currentScore=${String.format("%.2f", currentScore)}, candidate='${candidateWordNorm}', candidateScore=${String.format("%.2f", candidateScore)}, delta=${String.format("%.2f", candidateScore - currentScore)}, accepted=${acceptLookahead}")

                                if (acceptLookahead) {
                                    for (j in storyIndex until i) {
                                        if (wordStates[j] == WordState.PENDING) {
                                            wordStates[j] = WordState.SKIPPED
                                            ui.markWord(j, DebtUI.Color.RED)
                                            correctionBuffers.remove(j) // Point D
                                            ui.logMetric("skip_lookahead", "idx=${j}, reason='lookahead_to_idx_${i}'")
                                        }
                                    }
                                    wordStates[i] = WordState.CORRECT
                                    ui.markWord(i, DebtUI.Color.GREEN)
                                    correctionBuffers.remove(i) // Point D

                                    storyIndex = i + 1
                                    transcriptIndex++
                                    foundAhead = true
                                    lastLookaheadAt = System.currentTimeMillis() // Point F
                                    break
                                }
                            }
                        }
                    }

                    if (!foundAhead) {
                        wordStates[storyIndex] = WordState.INCORRECT
                        correctionBuffers.getOrPut(storyIndex) { ArrayDeque() }
                        ui.markWord(storyIndex, DebtUI.Color.RED)
                        ui.logMetric("mark_incorrect", "idx=${storyIndex}, story='${storyWords[storyIndex]}', transcript='${transcriptWord}'")
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
            val target = storyWordsNorm[cursorIndex] // Use normalized
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

        strictManager?.submitStrictCheck(debtIndex, candidateToken.text, storyWordsNorm[debtIndex], strictMatcher, threshold) { result -> // Use normalized
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
        correctionBuffers.remove(index) // Point D
        ui.logMetric("mark_correct", index)

        if (unreadDebtIndex == index) unreadDebtIndex = null
        if (index == cursorIndex) advanceCursorPastLocked()
    }

    private fun tryMatchToFollowing(token: RecognizedToken) {
        val limit = min(n - 1, cursorIndex + MAX_LOOKAHEAD)
        for (idx in (cursorIndex + 1)..limit) {
            if (wordStates[idx] == WordState.PENDING) {
                if (isFastMatch(token.text, storyWordsNorm[idx], isStrict = false)) { // Use normalized
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
        ui.logMetric("match_attempt", "story='${b}', transcript='${a}', score=${String.format("%.2f", score)}, threshold=${String.format("%.2f", threshold)}, strict=${isStrict}, match=${isMatch}")
        return isMatch
    }

    @Synchronized
    fun reset() {
        wordStates.fill(WordState.PENDING)
        cursorIndex = 0
        unreadDebtIndex = null
        correctionBuffers.clear()
        lastTranscribedWords = emptyList()
        lastLookaheadAt = 0L // Point F
        collector?.shutdown()
        strictManager?.shutdown()
    }
}

class CheapBackgroundCollector(
    private val manager: SpeechAligner,
    private val mode: Mode = Mode.Intermediate
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Debt-Collector-Thread").apply { isDaemon = true }
    }
    private val quickThreshold = 0.55f // A cheap heuristic threshold

    fun onDebtBufferUpdated(debtIndex: Int, bufferSnapshot: List<RecognizedToken>) {
        executor.execute {
            try {
                // This should also use the normalized story words, but manager doesn't expose them.
                // For now, it will use the original, which is a minor inconsistency.
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
