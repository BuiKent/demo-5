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
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.navArgs
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.FragmentStoryReadingBinding
import com.example.realtalkenglishwithAI.ui.composables.MicFab
import com.example.realtalkenglishwithAI.ui.composables.StoryContent
import com.example.realtalkenglishwithAI.utils.DebtUI
import com.example.realtalkenglishwithAI.utils.RecognizedToken
import com.example.realtalkenglishwithAI.utils.SpeechAligner
import com.example.realtalkenglishwithAI.utils.SpeechRecognitionManager
import com.example.realtalkenglishwithAI.viewmodel.StoryReadingViewModel

class StoryReadingFragment : Fragment(R.layout.fragment_story_reading),
    SpeechRecognitionManager.SpeechRecognitionManagerListener, DebtUI {

    private var _binding: FragmentStoryReadingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StoryReadingViewModel by viewModels()
    private val args: StoryReadingFragmentArgs by navArgs()

    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var speechAligner: SpeechAligner? = null
    private lateinit var prefs: SharedPreferences

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupStoryAndManagers()
        } else {
            showToast("Permission for microphone is required.")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentStoryReadingBinding.bind(view)

        prefs = requireContext().getSharedPreferences("realtalk_prefs", Context.MODE_PRIVATE)
        
        // Set toolbar title from arguments
        (activity as? AppCompatActivity)?.supportActionBar?.title = args.storyTitle

        binding.composeFabContainer.setContent {
            val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
            MicFab(isRecording = isRecording) { handleRecordAction() }
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
                setupStoryAndManagers()
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun setupStoryAndManagers() {
        if (speechAligner != null) return

        // Use story content from arguments
        val storyText = args.storyContent
        val storyWords = storyText.split(Regex("\\s+")).filter { it.isNotBlank() }

        viewModel.setWords(storyText)
        speechAligner = SpeechAligner(storyWords, this)

        if (speechRecognitionManager == null) {
            speechRecognitionManager = SpeechRecognitionManager(requireContext(), this, prefs)
        }

        speechRecognitionManager?.updateBiasingStrings(storyWords)
    }

    private fun handleRecordAction() {
        if (viewModel.isRecording.value) {
            speechRecognitionManager?.stopListening()
        } else {
            if (speechRecognitionManager == null || speechAligner == null) {
                checkPermissionsAndInit()
                return
            }
            speechAligner?.reset()
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

    override fun markWord(index: Int, color: DebtUI.Color) {
        val colorResId = when (color) {
            DebtUI.Color.GREEN -> R.color.word_correct
            DebtUI.Color.RED -> R.color.word_incorrect
            DebtUI.Color.DEFAULT -> R.color.word_default
        }
        val composeColor = Color(ContextCompat.getColor(requireContext(), colorResId))
        viewModel.updateWordColor(index, composeColor)
    }

    override fun onAdvanceCursorTo(index: Int) {
        viewModel.setWordFocus(index)
    }

    override fun showDebtMarker(index: Int) { /* For later */
    }

    override fun hideDebtMarker(index: Int) { /* For later */
    }

    override fun logMetric(key: String, value: Any) {
        Log.d("SpeechAlignerMetric", "$key: $value")
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechRecognitionManager?.destroy()
        speechAligner?.shutdown()
        _binding = null
    }
}
