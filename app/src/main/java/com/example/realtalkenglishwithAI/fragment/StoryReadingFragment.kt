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
import android.speech.SpeechRecognizer
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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

class StoryReadingFragment : Fragment(), SpeechRecognitionManager.SpeechRecognitionManagerListener {

    private var _binding: FragmentStoryReadingBinding? = null
    private val binding get() = _binding!!

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

    @Volatile private var lastLockedGlobalIndex: Int = -1
    @Volatile private var currentFocusedWordGlobalIndex: Int = -1

    // --- Hybrid 2.0 Processing --- //
    private lateinit var sequenceAligner: SequenceAligner
    private lateinit var realtimeAligner: RealtimeAlignmentProcessor
    private lateinit var levenshteinProcessor: LevenshteinProcessor // Keep for other potential uses
    private val deferredAlignmentHandler = Handler(Looper.getMainLooper())
    private var deferredAlignmentRunnable: Runnable? = null
    @Volatile private var lastPartialTranscript: String = ""
    @Volatile private var lastProcessedWordsForGreedy: List<String> = emptyList()
    private var difficultyLevel = 1 // 0: Beginner, 1: Intermediate, 2: Advanced
    private var lastPartialProcessTime = 0L

    // --- Colors --- //
    private var colorDefaultText: Int = Color.BLACK
    private var colorCorrectWord: Int = Color.GREEN
    private var colorIncorrectWord: Int = Color.RED
    private var colorFocusBackground: Int = Color.YELLOW
    private var fabWhiteColor: Int = Color.WHITE
    private var fabBlackColor: Int = Color.BLACK
    private var fabRedColor: Int = Color.RED

    // --- Data Classes for Caching --- //
    enum class WordMatchStatus { UNREAD, CORRECT, INCORRECT, SKIPPED }

    data class WordInfo(
        val originalText: String,
        val normalizedText: String,
        val metaphoneCode: String,
        val sentenceIndex: Int,
        val wordIndexInSentence: Int,
        val startCharInSentence: Int,
        val endCharInSentence: Int,
        var status: WordMatchStatus = WordMatchStatus.UNREAD
    )

    data class RecognizedInfo(
        val normalized: String,
        val metaphone: String
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                handleRecordAction()
            } else {
                Toast.makeText(requireContext(), "Record permission denied.", Toast.LENGTH_SHORT).show()
            }
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
            fabWhiteColor = ContextCompat.getColor(ctx, android.R.color.white)
            fabBlackColor = ContextCompat.getColor(ctx, android.R.color.black)
            fabRedColor = ContextCompat.getColor(ctx, R.color.red_500)
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

        levenshteinProcessor = LevenshteinProcessor()
        sequenceAligner = SequenceAligner()
        realtimeAligner = RealtimeAlignmentProcessor(levenshteinProcessor)

        setupSpeechManager()
        setupToolbar()
        setupMenu()
        displayInitialStoryContent(currentStoryContent!!, binding.linearLayoutResultsContainer)
        setupClickListeners()
        updateFabStates()
        showBeepWarningIfNeeded()
    }

    private fun setupSpeechManager() {
        speechManager = SpeechRecognitionManager(requireContext(), this, sharedPreferences)
    }

    private fun showBeepWarningIfNeeded() {
        val hasShownWarning = sharedPreferences.getBoolean(PREF_KEY_BEEP_WARNING_SHOWN, false)
        if (!hasShownWarning && isAdded) {
            context?.let {
                AlertDialog.Builder(it)
                    .setTitle("Audio Notice")
                    .setMessage("On some devices, a 'beep' sound from the speech recognizer cannot be muted. This is a one-time message.")
                    .setPositiveButton("OK") { _, _ -> sharedPreferences.edit().putBoolean(PREF_KEY_BEEP_WARNING_SHOWN, true).apply() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onPartialResults(transcript: String) {
        activity?.runOnUiThread {
            lastPartialTranscript = transcript
            runGreedyAlignment(transcript)

            deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacks(it) }
            deferredAlignmentRunnable = Runnable { runDeferredAlignment() }
            deferredAlignmentHandler.postDelayed(deferredAlignmentRunnable!!, DEFERRED_ALIGNMENT_DELAY_MS)
        }
    }

    override fun onFinalResults(transcript: String) {
        activity?.runOnUiThread {
            lastPartialTranscript = transcript
            deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacks(it) }
            runDeferredAlignment(isFinal = true)
        }
    }

    override fun onError(error: Int, isCritical: Boolean) {
        activity?.runOnUiThread {
            if (isCritical) {
                val errorMessage = SpeechRecognitionManager.getErrorText(error)
                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStateChanged(state: SpeechRecognitionManager.State) {
        activity?.runOnUiThread {
            isUserRecording = state == SpeechRecognitionManager.State.LISTENING
            updateFabStates()
        }
    }

    private fun runGreedyAlignment(partialTranscript: String) {
        if (!isAdded || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        val now = System.currentTimeMillis()
        if (now - lastPartialProcessTime < MIN_PARTIAL_PROCESS_INTERVAL_MS) {
            return // Debounce
        }
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

            processSkippedWords() // Amnesty Mechanism
        }
    }

    private fun runDeferredAlignment(isFinal: Boolean = false) {
        if (!isAdded || (!isUserRecording && !isFinal)) return

        val recognizedWords = lastPartialTranscript.trim().split(Regex("\\s+"))
        if (recognizedWords.size < MIN_RECOGNIZED_WORDS_FOR_ALIGNMENT) {
            if (!isFinal) return
        }

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
            val alignmentResult = sequenceAligner.alignWindow(
                storyWordsWindow = storyWindow, 
                recognizedWords = recognizedInfos, 
                difficultyLevel = difficultyLevel,
                params = params
            )

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
                if (sIdx >= 0 && sIdx < sentenceTextViews.size)
                    sentenceTextViews[sIdx].setText(sentenceSpannables[sIdx], TextView.BufferType.SPANNABLE)
            }

            updateStoryWordFocus((lastLockedGlobalIndex + 1).coerceAtMost(allWordInfosInStory.size - 1))

            processSkippedWords() // Amnesty Mechanism

            if (isFinal) {
                lastPartialTranscript = ""
                lastProcessedWordsForGreedy = emptyList()
                realtimeAligner.reset()
            }
        }
    }

    private fun processSkippedWords() {
        val sentencesToUpdate = mutableSetOf<Int>()
        allWordInfosInStory.forEachIndexed { index, wordInfo ->
            // Check if a word was skipped and its amnesty period is over
            if (wordInfo.status == WordMatchStatus.SKIPPED && index < lastLockedGlobalIndex - AMNESTY_DISTANCE) {
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

    private fun updateFabStates() {
        binding.buttonRecordStorySentence.apply {
            setImageResource(if (isUserRecording) R.drawable.ic_stop else R.drawable.ic_mic)
            backgroundTintList = ColorStateList.valueOf(if (isUserRecording) fabRedColor else fabWhiteColor)
            imageTintList = ColorStateList.valueOf(if (isUserRecording) fabWhiteColor else fabBlackColor)
            isEnabled = speechManager.isAvailable
            alpha = if (speechManager.isAvailable) 1.0f else 0.5f
        }
    }

    private fun displayInitialStoryContent(storyText: String, container: LinearLayout) {
        container.removeAllViews()
        sentenceTextViews.clear(); sentenceSpannables.clear(); allWordInfosInStory.clear()
        lastLockedGlobalIndex = -1

        val metaphoneEncoder = DoubleMetaphone()
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
                        allWordInfosInStory.add(WordInfo(word, normalizedWord, metaphoneEncoder.encode(normalizedWord) ?: "", sentenceIdx, wordIdxInSent, start, end))
                        currentWordStartChar = end
                    }
                }
            }
        }
    }

    private fun resetAllWordStatesAndColors() {
        synchronized(wordStateLock) {
            deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacks(it) }
            updateStoryWordFocus(-1)
            allWordInfosInStory.forEach {
                if (it.status != WordMatchStatus.UNREAD) {
                    it.status = WordMatchStatus.UNREAD
                    updateWordSpanNoFlush(it, colorDefaultText, null)
                }
            }
            lastLockedGlobalIndex = -1
            lastPartialTranscript = ""
            lastProcessedWordsForGreedy = emptyList()
            realtimeAligner.reset()
            sentenceSpannables.forEachIndexed { index, _ -> sentenceTextViews[index].setText(sentenceSpannables[index], TextView.BufferType.SPANNABLE) }
        }
    }

    private fun updateStoryWordFocus(newFocusGlobalIndex: Int) {
        val oldFocusIndex = currentFocusedWordGlobalIndex
        currentFocusedWordGlobalIndex = newFocusGlobalIndex

        if (oldFocusIndex != -1 && oldFocusIndex < allWordInfosInStory.size) {
            val oldWordInfo = allWordInfosInStory[oldFocusIndex]
            if (oldWordInfo.status != WordMatchStatus.CORRECT) {
                updateWordSpanNoFlush(oldWordInfo, colorDefaultText, null)?.let { sentenceTextViews[it].setText(sentenceSpannables[it], TextView.BufferType.SPANNABLE) }
            }
        }

        if (newFocusGlobalIndex != -1 && newFocusGlobalIndex < allWordInfosInStory.size) {
            val newWordInfo = allWordInfosInStory[newFocusGlobalIndex]
            if (newWordInfo.status != WordMatchStatus.CORRECT) {
                updateWordSpanNoFlush(newWordInfo, colorDefaultText, colorFocusBackground)?.let { sentenceTextViews[it].setText(sentenceSpannables[it], TextView.BufferType.SPANNABLE) }
            }
        }
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

    private fun normalizeToken(s: String): String = s.lowercase().replace(Regex("[^a-z0-9']"), "")

    override fun onDestroyView() {
        super.onDestroyView()
        deferredAlignmentRunnable?.let { deferredAlignmentHandler.removeCallbacksAndMessages(null) }
        if (this::speechManager.isInitialized) {
            speechManager.destroy()
        }
        _binding = null
    }

    companion object {
        private const val ARG_STORY_CONTENT = "story_content"
        private const val ARG_STORY_TITLE = "story_title"
        private const val TAG = "StoryReadingFragment"
        private const val PREF_KEY_BEEP_WARNING_SHOWN = "beep_warning_shown"
        private const val DEFERRED_ALIGNMENT_DELAY_MS = 400L
        private const val ALIGNMENT_WINDOW_SIZE = 30
        private const val MIN_RECOGNIZED_WORDS_FOR_ALIGNMENT = 2
        private const val MIN_PARTIAL_PROCESS_INTERVAL_MS = 100L
        private const val AMNESTY_DISTANCE = 5 // Distance for the Amnesty Mechanism
    }
}