package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.navArgs
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.FragmentStoryReadingBinding
import com.example.realtalkenglishwithAI.ui.composables.MicFab
import com.example.realtalkenglishwithAI.ui.composables.StoryContent
import com.example.realtalkenglishwithAI.utils.*
import com.example.realtalkenglishwithAI.viewmodel.StoryReadingViewModel
import com.example.realtalkenglishwithAI.utils.Mode as DebtMode

class StoryReadingFragment : Fragment(R.layout.fragment_story_reading),
    SpeechRecognitionManager.SpeechRecognitionManagerListener, DebtUI {

    private var _binding: FragmentStoryReadingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StoryReadingViewModel by viewModels()
    private val args: StoryReadingFragmentArgs by navArgs()

    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var speechAligner: SpeechAligner? = null
    private var strictManager: StrictCorrectionManager? = null
    private lateinit var prefs: SharedPreferences
    private var difficultyLevel = 1 // 0: Beginner, 1: Intermediate, 2: Advanced

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            handleRecordAction()
        } else {
            showToast("Permission for microphone is required.")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentStoryReadingBinding.bind(view)

        prefs = requireActivity().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        difficultyLevel = prefs.getInt("DIFFICULTY_LEVEL", 1)

        (activity as? AppCompatActivity)?.supportActionBar?.title = args.storyTitle

        setupSpeechManager()

        binding.composeFabContainer.setContent {
            val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Restart Button
                FloatingActionButton(
                    onClick = { handleRestartAction() },
                    containerColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.border(1.dp, Color.Black, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Restart story",
                        tint = Color.Black
                    )
                }

                // Mic Button (Pause/Resume)
                MicFab(isRecording = isRecording) { handleRecordAction() }
            }
        }
        binding.contentMain.composeStoryContainer.setContent {
            val words by viewModel.words.collectAsStateWithLifecycle()
            StoryContent(words = words)
        }

        checkPermissionsAndInit()
    }

    private fun checkPermissionsAndInit() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeSystems()
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun setupSpeechManager() {
        speechRecognitionManager = SpeechRecognitionManager(requireContext(), this, prefs)
    }

    private fun initializeSystems() {
        if (speechAligner != null) return

        val storyText = args.storyContent
        val storyWords = storyText.split(Regex("\\s+")).filter { it.isNotBlank() }

        viewModel.setWords(storyText)

        val currentMode = when(difficultyLevel) {
            0 -> DebtMode.Beginner
            1 -> DebtMode.Intermediate
            2 -> DebtMode.Advanced
            else -> DebtMode.Intermediate
        }

        speechAligner = SpeechAligner(storyWords, this, currentMode).apply {
            if (currentMode == DebtMode.Advanced) {
                // In a real scenario, you might have a feature flag check here
                this.collector = CheapBackgroundCollector(this, currentMode)
                this@StoryReadingFragment.strictManager = StrictCorrectionManager()
                this.strictManager = this@StoryReadingFragment.strictManager
            }
        }
        logMetric("system_initialized", "mode=${currentMode.name}, words=${storyWords.size}")
    }

    private fun handleRestartAction() {
        if (speechRecognitionManager == null || speechAligner == null) {
            checkPermissionsAndInit()
            return
        }
        // CORRECTED: Reset the UI state by re-initializing the words in the ViewModel
        viewModel.setWords(args.storyContent)

        speechAligner?.reset() // Reset backend cursor to beginning
        speechRecognitionManager?.startListening() // Start recording
    }

    private fun handleRecordAction() {
        if (viewModel.isRecording.value) {
            speechRecognitionManager?.stopListening() // This is now PAUSE
        } else {
            if (speechRecognitionManager == null || speechAligner == null) {
                checkPermissionsAndInit()
                return
            }
            // `speechAligner?.reset()` is removed. This is now RESUME.
            speechRecognitionManager?.startListening()
        }
    }

    override fun onStateChanged(state: SpeechRecognitionManager.State) {
        val isRecording = state == SpeechRecognitionManager.State.LISTENING
        viewModel.setRecording(isRecording)
    }

    override fun onPartialResults(transcript: String) {
        val tokens = transcript.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { RecognizedToken(text = it) }
        speechAligner?.onRecognizedWords(tokens)
    }

    override fun onFinalResults(transcript: String) {
        val tokens = transcript.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { RecognizedToken(text = it) }
        speechAligner?.onRecognizedWords(tokens)
    }

    override fun onError(error: Int, isCritical: Boolean) {
        val errorMessage = SpeechRecognitionManager.getErrorText(error)
        showToast("Recognition error: $errorMessage")
        viewModel.setRecording(false)
    }

    // --- DebtUI Interface Implementation ---

    override fun markWord(index: Int, color: DebtUI.Color) {
        val colorResId = when (color) {
            DebtUI.Color.GREEN -> R.color.word_correct
            DebtUI.Color.RED -> R.color.word_incorrect
            DebtUI.Color.DEFAULT -> R.color.word_default
        }
        val composeColor = Color(ContextCompat.getColor(requireContext(), colorResId))
        viewModel.updateWordColor(index, composeColor)
    }

    override fun showDebtMarker(index: Int) {
        // This can be mapped to a specific UI state in the ViewModel if needed
        // For now, we can log it.
        logMetric("show_debt_marker", "index=$index")
    }

    override fun hideDebtMarker(index: Int) {
        logMetric("hide_debt_marker", "index=$index")
    }

    override fun onAdvanceCursorTo(index: Int) {
        viewModel.setWordFocus(index)
    }

    override fun logMetric(key: String, value: Any) {
        Log.d("RealTalkMetrics", "$key: $value")
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechRecognitionManager?.destroy()
        speechAligner?.shutdown()
        strictManager?.shutdown()
        _binding = null
    }
}
