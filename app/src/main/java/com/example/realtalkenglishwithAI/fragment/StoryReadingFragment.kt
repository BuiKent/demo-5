package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.FragmentStoryReadingBinding
import com.example.realtalkenglishwithAI.utils.*
import org.apache.commons.codec.language.DoubleMetaphone
import com.example.realtalkenglishwithAI.utils.Mode as DebtMode

class StoryReadingFragment : Fragment(), SpeechRecognitionManager.SpeechRecognitionManagerListener, DebtUI {

    private var _binding: FragmentStoryReadingBinding? = null
    private val binding get() = _binding!!

    // --- Config --- //
    private val isPhase0Enabled = true // MASTER SWITCH

    // --- Speech Recognition --- //
    private lateinit var speechManager: SpeechRecognitionManager
    private lateinit var sharedPreferences: SharedPreferences
    @Volatile private var isUserRecording = false

    // --- Story and Word State --- //
    private var currentStoryContent: String? = null
    private var currentStoryTitle: String? = null
    private val sentenceTextViews = mutableListOf<TextView>()
    private val sentenceSpannables = mutableListOf<SpannableString>()
    private val allWordInfosInStory = mutableListOf<WordInfo>()
    private val wordStateLock = Any()
    private var difficultyLevel = 1 // 0: Beginner, 1: Intermediate, 2: Advanced

    // --- Phase 0: Debt-based System --- //
    private var phase0Manager: Phase0Manager? = null

    // --- Legacy System State --- //
    @Volatile private var lastLockedGlobalIndex: Int = -1
    @Volatile private var currentFocusedWordGlobalIndex: Int = -1
    private lateinit var sequenceAligner: SequenceAligner
    private lateinit var realtimeAligner: RealtimeAlignmentProcessor
    private val deferredAlignmentHandler = Handler(Looper.getMainLooper())
    private var deferredAlignmentRunnable: Runnable? = null
    @Volatile private var lastPartialTranscript: String = ""
    @Volatile private var lastProcessedWordsForGreedy: List<String> = emptyList()
    private var lastPartialProcessTime = 0L

    // --- Colors --- //
    private var colorDefaultText: Int = Color.BLACK
    private var colorCorrectWord: Int = Color.GREEN
    private var colorIncorrectWord: Int = Color.RED
    private var colorFocusBackground: Int = Color.YELLOW

    // --- Data Classes (Restored for compatibility with Legacy System) ---
    enum class WordMatchStatus { UNREAD, CORRECT, INCORRECT, SKIPPED }

    data class WordInfo(
        val originalText: String,
        val normalizedText: String,
        val metaphoneCode: String, // RESTORED
        val sentenceIndex: Int,
        val wordIndexInSentence: Int, // RESTORED
        val startCharInSentence: Int,
        val endCharInSentence: Int,
        var status: WordMatchStatus = WordMatchStatus.UNREAD // RESTORED
    )

    data class RecognizedInfo(
        val normalized: String,
        val metaphone: String
    ) // RESTORED

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) handleRecordAction() else Toast.makeText(requireContext(), "Record permission denied.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            currentStoryContent = it.getString(ARG_STORY_CONTENT)
            currentStoryTitle = it.getString(ARG_STORY_TITLE)
        }
        if (currentStoryContent == null || currentStoryTitle == null) {
            findNavController().popBackStack()
            return
        }
        context?.let { ctx ->
            colorDefaultText = ContextCompat.getColor(ctx, android.R.color.black)
            colorCorrectWord = ContextCompat.getColor(ctx, R.color.blue_500)
            colorIncorrectWord = ContextCompat.getColor(ctx, R.color.red_500)
            colorFocusBackground = ContextCompat.getColor(ctx, R.color.focused_word_background)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStoryReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (currentStoryContent == null || currentStoryTitle == null) return

        sharedPreferences = requireActivity().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        difficultyLevel = sharedPreferences.getInt("DIFFICULTY_LEVEL", 1)

        // Initialize both systems' components
        sequenceAligner = SequenceAligner()
        realtimeAligner = RealtimeAlignmentProcessor(LevenshteinProcessor())

        setupSpeechManager()
        setupToolbar()
        setupMenu()
        displayInitialStoryContent(currentStoryContent!!, binding.linearLayoutResultsContainer)
        setupClickListeners()
        updateFabStates()
    }

    // --- Setup Methods --- //

    private fun setupSpeechManager() {
        speechManager = SpeechRecognitionManager(requireContext(), this, sharedPreferences)
    }

    private fun setupToolbar() {
        (activity as AppCompatActivity).setSupportActionBar(binding.toolbarStoryReading)
        (activity as? AppCompatActivity)?.supportActionBar?.title = currentStoryTitle ?: "Story"
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupMenu() {
        (requireActivity() as MenuHost).addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.story_reading_menu, menu)
            }
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == android.R.id.home) {
                    findNavController().navigateUp()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    // --- Speech Recognition Callbacks --- //

    override fun onPartialResults(transcript: String) {
        if (isPhase0Enabled) {
            val tokens = transcript.split(Regex("\\s+")).filter { it.isNotBlank() }.map { RecognizedToken(it) }
            phase0Manager?.onRecognizedWords(tokens)
        } else {
            activity?.runOnUiThread {
                lastPartialTranscript = transcript
                runGreedyAlignment(transcript)
                deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacks(it) }
                deferredAlignmentRunnable = Runnable { runDeferredAlignment() }
                deferredAlignmentHandler.postDelayed(deferredAlignmentRunnable!!, DEFERRED_ALIGNMENT_DELAY_MS)
            }
        }
    }

    override fun onFinalResults(transcript: String) {
        if (isPhase0Enabled) {
            val tokens = transcript.split(Regex("\\s+")).filter { it.isNotBlank() }.map { RecognizedToken(it) }
            phase0Manager?.onRecognizedWords(tokens)
        } else {
            activity?.runOnUiThread {
                lastPartialTranscript = transcript
                deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacks(it) }
                runDeferredAlignment(isFinal = true)
            }
        }
    }

    override fun onError(error: Int, isCritical: Boolean) {
        activity?.runOnUiThread {
            if (isCritical) Toast.makeText(requireContext(), SpeechRecognitionManager.getErrorText(error), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStateChanged(state: SpeechRecognitionManager.State) {
        activity?.runOnUiThread {
            isUserRecording = state == SpeechRecognitionManager.State.LISTENING
            updateFabStates()
        }
    }

    // --- DebtUI Interface Implementation --- //

    override fun markWord(index: Int, color: DebtUI.Color, locked: Boolean) {
        val wordInfo = allWordInfosInStory.getOrNull(index) ?: return
        val textColor = when (color) {
            DebtUI.Color.GREEN -> colorCorrectWord
            DebtUI.Color.RED -> colorIncorrectWord
            else -> colorDefaultText
        }
        updateWordSpan(wordInfo, textColor, null, removeAllSpans = true)
    }

    override fun showDebtMarker(index: Int) {
        val wordInfo = allWordInfosInStory.getOrNull(index) ?: return
        val spannable = sentenceSpannables[wordInfo.sentenceIndex]
        val start = wordInfo.startCharInSentence
        val end = wordInfo.endCharInSentence
        if (start < 0 || end > spannable.length) return
        spannable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sentenceTextViews[wordInfo.sentenceIndex].text = spannable
    }

    override fun hideDebtMarker(index: Int) {
        val wordInfo = allWordInfosInStory.getOrNull(index) ?: return
        val spannable = sentenceSpannables[wordInfo.sentenceIndex]
        val start = wordInfo.startCharInSentence
        val end = wordInfo.endCharInSentence
        if (start < 0 || end > spannable.length) return

        spannable.getSpans(start, end, UnderlineSpan::class.java).forEach { spannable.removeSpan(it) }
        sentenceTextViews[wordInfo.sentenceIndex].text = spannable
    }

    override fun onAdvanceCursorTo(index: Int) {
        if (!isPhase0Enabled) return
        updateStoryWordFocus(index)
    }

    override fun logMetric(key: String, value: Any) {
        Log.d(TAG, "Phase0Metric - $key: $value")
    }

    // --- UI Update and Management --- //

    private fun displayInitialStoryContent(storyText: String, container: LinearLayout) {
        container.removeAllViews()
        sentenceTextViews.clear(); sentenceSpannables.clear(); allWordInfosInStory.clear()
        lastLockedGlobalIndex = -1

        val metaphoneEncoder = DoubleMetaphone() // RESTORED
        val sentences = storyText.split(Regex("(?<=[.!?;:])\\s+")).filter { it.isNotBlank() }

        sentences.forEachIndexed { sentenceIdx, sentenceStr ->
            val spannable = SpannableString(sentenceStr)
            TextView(requireContext()).apply {
                text = spannable
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(colorDefaultText)
                setPadding(4, 8, 4, 8)
                setLineSpacing(0f, 1.2f)
            }.also { container.addView(it); sentenceTextViews.add(it) }
            sentenceSpannables.add(spannable)

            var currentWordStartChar = 0
            sentenceStr.split(Regex("\\s+")).filter { it.isNotBlank() }.forEachIndexed { wordIdxInSent, word ->
                val normalizedWord = normalizeToken(word)
                if (normalizedWord.isNotEmpty()) {
                    val start = sentenceStr.indexOf(word, currentWordStartChar)
                    if (start != -1) {
                        val end = start + word.length
                        // RESTORED: Add full WordInfo with metaphone
                        allWordInfosInStory.add(WordInfo(word, normalizedWord, metaphoneEncoder.encode(normalizedWord) ?: "", sentenceIdx, wordIdxInSent, start, end))
                        currentWordStartChar = end
                    }
                }
            }
        }

        if (isPhase0Enabled) {
            initializePhase0()
        }
    }

    private fun resetAllWordStatesAndColors() {
        if (isPhase0Enabled) {
            phase0Manager?.reset()
            initializePhase0()
        } else {
            // Legacy reset logic
            synchronized(wordStateLock) {
                deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacks(it) }
                allWordInfosInStory.forEach { it.status = WordMatchStatus.UNREAD }
                lastLockedGlobalIndex = -1
                lastPartialTranscript = ""
                lastProcessedWordsForGreedy = emptyList()
                realtimeAligner.reset()
            }
        }

        // Common UI reset for both systems
        synchronized(wordStateLock) {
            updateStoryWordFocus(-1)
            allWordInfosInStory.forEachIndexed { _, info -> // FIXED: Renamed 'index' to '_'
                updateWordSpan(info, colorDefaultText, null, removeAllSpans = true)
            }
        }
    }

    private fun updateStoryWordFocus(newFocusGlobalIndex: Int) {
        activity?.runOnUiThread {
            val oldFocusIndex = currentFocusedWordGlobalIndex
            if (oldFocusIndex == newFocusGlobalIndex && !isPhase0Enabled) return@runOnUiThread // Allow re-focus in Phase 0
            currentFocusedWordGlobalIndex = newFocusGlobalIndex

            if (oldFocusIndex != -1) {
                allWordInfosInStory.getOrNull(oldFocusIndex)?.let { oldWord ->
                    val isOldWordCorrect = if (isPhase0Enabled) phase0Manager?.wordStates?.getOrNull(oldFocusIndex) == com.example.realtalkenglishwithAI.utils.WordState.CORRECT else oldWord.status == WordMatchStatus.CORRECT
                    if (!isOldWordCorrect) {
                         updateWordSpan(oldWord, colorDefaultText, null)
                    }
                }
            }

            if (newFocusGlobalIndex != -1) {
                allWordInfosInStory.getOrNull(newFocusGlobalIndex)?.let { newWord ->
                    val isNewWordCorrect = if (isPhase0Enabled) phase0Manager?.wordStates?.getOrNull(newFocusGlobalIndex) == com.example.realtalkenglishwithAI.utils.WordState.CORRECT else newWord.status == WordMatchStatus.CORRECT
                    if (!isNewWordCorrect) {
                        updateWordSpan(newWord, colorDefaultText, colorFocusBackground)
                    }
                }
            }
        }
    }

    private fun updateWordSpan(wordInfo: WordInfo, textColor: Int, newBackgroundColor: Int?, removeAllSpans: Boolean = false) {
        if (wordInfo.sentenceIndex >= sentenceSpannables.size) return
        val spannable = sentenceSpannables[wordInfo.sentenceIndex]
        val start = wordInfo.startCharInSentence
        val end = wordInfo.endCharInSentence
        if (start < 0 || end > spannable.length || start >= end) return

        if (removeAllSpans) {
             spannable.getSpans(start, end, Any::class.java).forEach { spannable.removeSpan(it) }
        }
        spannable.getSpans(start, end, BackgroundColorSpan::class.java).forEach { spannable.removeSpan(it) }

        spannable.setSpan(ForegroundColorSpan(textColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        newBackgroundColor?.let { spannable.setSpan(BackgroundColorSpan(it), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        
        sentenceTextViews[wordInfo.sentenceIndex].text = spannable
    }
    
    private fun updateWordSpanNoFlush(wordInfo: WordInfo, textColor: Int, newBackgroundColor: Int?): Int? {
        if (wordInfo.sentenceIndex >= sentenceSpannables.size) return null
        val spannable = sentenceSpannables[wordInfo.sentenceIndex]
        val start = wordInfo.startCharInSentence
        val end = wordInfo.endCharInSentence
        if (start < 0 || end > spannable.length || start >= end) return null

        spannable.getSpans(start, end, Any::class.java).forEach { spannable.removeSpan(it) }
        spannable.setSpan(ForegroundColorSpan(textColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        newBackgroundColor?.let { spannable.setSpan(BackgroundColorSpan(it), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return wordInfo.sentenceIndex
    }

    private fun setupClickListeners() {
        binding.buttonRecordStorySentence.setOnClickListener { handleRecordAction() }
    }

    private fun handleRecordAction() {
        if (!this::speechManager.isInitialized || !speechManager.isAvailable) {
            Toast.makeText(requireContext(), "Speech recognizer is not available.", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (isUserRecording) {
            speechManager.stopListening()
        } else {
            resetAllWordStatesAndColors()
            speechManager.startListening()
        }
    }

     private fun initializePhase0() {
        if (!isPhase0Enabled) return
        val storyWords = allWordInfosInStory.map { it.normalizedText }
        val currentMode = when(difficultyLevel) {
            0 -> DebtMode.BEGINNER
            1 -> DebtMode.MEDIUM
            2 -> DebtMode.ADVANCED
            else -> DebtMode.MEDIUM
        }
        phase0Manager = Phase0Manager(storyWords, this, currentMode).apply {
            collector = CheapBackgroundCollector(this, currentMode)
        }
        Log.d(TAG, "Phase 0 Manager Initialized with ${storyWords.size} words.")
    }

    private fun normalizeToken(s: String): String = s.lowercase().replace(Regex("[^a-z0-9']"), "")

    override fun onDestroyView() {
        super.onDestroyView()
        if (isPhase0Enabled) {
            phase0Manager?.reset()
        } else {
            deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacksAndMessages(null) }
        }
        if (this::speechManager.isInitialized) {
            speechManager.destroy()
        }
        _binding = null
    }
    
    // --- Legacy System (Full Implementation Restored) --- //

    private fun runGreedyAlignment(partialTranscript: String) {
        if (!isAdded || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        val now = System.currentTimeMillis()
        if (now - lastPartialProcessTime < MIN_PARTIAL_PROCESS_INTERVAL_MS) return
        lastPartialProcessTime = now

        synchronized(wordStateLock) {
            val result = realtimeAligner.process(
                partialTranscript = partialTranscript,
                lastProcessedWords = lastProcessedWordsForGreedy,
                lastLockedGlobalIndex = lastLockedGlobalIndex,
                allWordInfosInStory = allWordInfosInStory,
                difficultyLevel = difficultyLevel,
                isUserRecording = isUserRecording
            )
            lastLockedGlobalIndex = result.newLockedGlobalIndex
            lastProcessedWordsForGreedy = result.newProcessedWords
            result.dirtySentences.forEach { sIdx ->
                allWordInfosInStory.filter { it.sentenceIndex == sIdx }.forEach { wordInfo ->
                    val color = when(wordInfo.status) {
                        WordMatchStatus.CORRECT -> colorCorrectWord
                        WordMatchStatus.INCORRECT -> colorIncorrectWord
                        else -> colorDefaultText
                    }
                    updateWordSpanNoFlush(wordInfo, color, null)
                }
            }
            result.dirtySentences.forEach { sIdx ->
                if (sIdx >= 0 && sIdx < sentenceTextViews.size) {
                    sentenceTextViews[sIdx].setText(sentenceSpannables[sIdx], TextView.BufferType.SPANNABLE)
                }
            }
            if (result.newFocusCandidate != currentFocusedWordGlobalIndex) {
                updateStoryWordFocus(result.newFocusCandidate)
            }
            processSkippedWords()
        }
    }

    private fun runDeferredAlignment(isFinal: Boolean = false) {
        if (!isAdded || (!isUserRecording && !isFinal)) return
        val recognizedWords = lastPartialTranscript.trim().split(Regex("\\s+"))
        if (recognizedWords.isEmpty()) return

        synchronized(wordStateLock) {
            val startIndex = lastLockedGlobalIndex + 1
            if (startIndex >= allWordInfosInStory.size) return

            val endIndex = (startIndex + ALIGNMENT_WINDOW_SIZE).coerceAtMost(allWordInfosInStory.size)
            val storyWindow = allWordInfosInStory.subList(startIndex, endIndex)
            val metaphoneEncoder = DoubleMetaphone()
            val recognizedInfos = recognizedWords.map { word ->
                val normalized = normalizeToken(word)
                RecognizedInfo(normalized, metaphoneEncoder.encode(normalized) ?: "")
            }.filter { it.normalized.isNotBlank() }

            if (recognizedInfos.isEmpty()) return

            val params = getAlignmentParams(difficultyLevel, AlignmentLevel.SENTENCE)
            val alignmentResult = sequenceAligner.alignWindow(storyWindow, recognizedInfos, difficultyLevel, params)

            var newLockedIndexInWindow = -1
            var progressMade = false
            val dirtySentences = mutableSetOf<Int>()

            for ((index, move) in alignmentResult.moves.withIndex()) {
                val storyWord = alignmentResult.alignedStoryWords.getOrNull(index)
                if (storyWord != null) {
                    val currentWordIndexInWindow = storyWindow.indexOf(storyWord)
                    when (move) {
                        AlignmentMove.MATCH -> {
                            if (storyWord.status != WordMatchStatus.CORRECT) {
                                storyWord.status = WordMatchStatus.CORRECT
                                updateWordSpanNoFlush(storyWord, colorCorrectWord, null)
                                dirtySentences.add(storyWord.sentenceIndex)
                            }
                            newLockedIndexInWindow = currentWordIndexInWindow
                            progressMade = true
                        }
                        AlignmentMove.SUBSTITUTION, AlignmentMove.DELETION -> {
                            if (storyWord.status == WordMatchStatus.UNREAD) {
                                storyWord.status = WordMatchStatus.SKIPPED
                            }
                            newLockedIndexInWindow = currentWordIndexInWindow
                            progressMade = true
                        }
                        AlignmentMove.INSERTION -> { /* Do nothing */ }
                    }
                }
            }
            
            if (progressMade && newLockedIndexInWindow != -1) {
                lastLockedGlobalIndex = startIndex + newLockedIndexInWindow
            }
            dirtySentences.forEach { sIdx ->
                if (sIdx >= 0 && sIdx < sentenceTextViews.size) sentenceTextViews[sIdx].setText(sentenceSpannables[sIdx], TextView.BufferType.SPANNABLE)
            }
            updateStoryWordFocus((lastLockedGlobalIndex + 1).coerceAtMost(allWordInfosInStory.size - 1))
            processSkippedWords()

            if (isFinal) {
                lastPartialTranscript = ""
                lastProcessedWordsForGreedy = emptyList()
                realtimeAligner.reset()
            }
        }
    }

    private fun getAmnestyDistance(): Int {
        return when (difficultyLevel) {
            0 -> 7 // Easy
            1 -> 5 // Medium
            2 -> 3 // Hard
            else -> 5 // Default
        }
    }

    private fun processSkippedWords() {
        val sentencesToUpdate = mutableSetOf<Int>()
        val amnestyDistance = getAmnestyDistance()
        allWordInfosInStory.forEachIndexed { index, wordInfo ->
            if (wordInfo.status == WordMatchStatus.SKIPPED && index < lastLockedGlobalIndex - amnestyDistance) {
                wordInfo.status = WordMatchStatus.INCORRECT
                updateWordSpanNoFlush(wordInfo, colorIncorrectWord, null)
                sentencesToUpdate.add(wordInfo.sentenceIndex)
            }
        }
        if (sentencesToUpdate.isNotEmpty()) {
            activity?.runOnUiThread {
                sentencesToUpdate.forEach { sIdx ->
                    if (sIdx >= 0 && sIdx < sentenceTextViews.size) {
                        sentenceTextViews[sIdx].setText(sentenceSpannables[sIdx], TextView.BufferType.SPANNABLE)
                    }
                }
            }
        }
    }

    private fun updateFabStates() {
        binding.buttonRecordStorySentence.apply {
            val tint = if (isUserRecording) Color.WHITE else Color.BLACK
            setImageResource(if (isUserRecording) R.drawable.ic_stop else R.drawable.ic_mic)
            backgroundTintList = ColorStateList.valueOf(if (isUserRecording) colorIncorrectWord else Color.WHITE)
            imageTintList = ColorStateList.valueOf(tint)
        }
    }

    companion object {
        private const val ARG_STORY_CONTENT = "story_content"
        private const val ARG_STORY_TITLE = "story_title"
        private const val TAG = "StoryReadingFragment"
        private const val DEFERRED_ALIGNMENT_DELAY_MS = 400L
        private const val ALIGNMENT_WINDOW_SIZE = 30
        private const val MIN_PARTIAL_PROCESS_INTERVAL_MS = 100L
    }
}