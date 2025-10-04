package com.example.realtalkenglishwithAI.features.story_reading.ui

import android.Manifest
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.ui.composables.MicFab
import com.example.realtalkenglishwithAI.ui.composables.StoryContent
import com.example.realtalkenglishwithAI.utils.*
import com.example.realtalkenglishwithAI.viewmodel.StoryReadingViewModel
import com.example.realtalkenglishwithAI.utils.Mode as DebtMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryReadingScreen(
    storyTitle: String?,
    storyContent: String?,
) {
    val context = LocalContext.current
    val viewModel: StoryReadingViewModel = viewModel()

    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val words by viewModel.words.collectAsStateWithLifecycle()

    // State for managing the speech recognition and alignment systems
    var speechRecognitionManager by remember { mutableStateOf<SpeechRecognitionManager?>(null) }
    var speechAligner by remember { mutableStateOf<SpeechAligner?>(null) }
    var strictManager by remember { mutableStateOf<StrictCorrectionManager?>(null) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(context, "Permission for microphone is required.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- ACTION HANDLERS ---
    val handleRecordAction = {
        if (isRecording) {
            speechRecognitionManager?.stopListening() // Pause
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                speechRecognitionManager?.startListening() // Resume
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val handleRestartAction = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            viewModel.setWords(storyContent ?: "")
            speechAligner?.reset()
            speechRecognitionManager?.startListening()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // --- LIFECYCLE MANAGEMENT ---
    LaunchedEffect(storyContent) {
        if (storyContent == null) return@LaunchedEffect

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val difficultyLevel = prefs.getInt("DIFFICULTY_LEVEL", 1)
        val currentMode = when(difficultyLevel) {
            0 -> DebtMode.Beginner
            1 -> DebtMode.Intermediate
            2 -> DebtMode.Advanced
            else -> DebtMode.Intermediate
        }

        val debtUI = object : DebtUI {
            override fun markWord(index: Int, color: DebtUI.Color) {
                val colorResId = when (color) {
                    DebtUI.Color.GREEN -> R.color.word_correct
                    DebtUI.Color.RED -> R.color.word_incorrect
                    DebtUI.Color.DEFAULT -> R.color.word_default
                }
                viewModel.updateWordColor(index, Color(ContextCompat.getColor(context, colorResId)))
            }
            override fun onAdvanceCursorTo(index: Int) { viewModel.setWordFocus(index) }
            override fun showDebtMarker(index: Int) { Log.d("RealTalkMetrics", "show_debt_marker: $index") }
            override fun hideDebtMarker(index: Int) { Log.d("RealTalkMetrics", "hide_debt_marker: $index") }
            override fun logMetric(key: String, value: Any) { Log.d("RealTalkMetrics", "$key: $value") }
        }

        val storyWords = storyContent.split(Regex("\\s+")).filter { it.isNotBlank() }
        viewModel.setWords(storyContent)
        
        speechAligner = SpeechAligner(storyWords, debtUI, currentMode).apply {
            if (currentMode == DebtMode.Advanced) {
                this.collector = CheapBackgroundCollector(this, currentMode)
                val newStrictManager = StrictCorrectionManager()
                strictManager = newStrictManager
                this.strictManager = newStrictManager
            }
        }

        val speechListener = object : SpeechRecognitionManager.SpeechRecognitionManagerListener {
            override fun onStateChanged(state: SpeechRecognitionManager.State) {
                viewModel.setRecording(state == SpeechRecognitionManager.State.LISTENING)
            }
            override fun onPartialResults(transcript: String) {
                speechAligner?.onRecognizedWords(transcript.split(Regex("\\s+")).filter { it.isNotBlank() }.map { RecognizedToken(text = it) })
            }
            override fun onFinalResults(transcript: String) {
                speechAligner?.onRecognizedWords(transcript.split(Regex("\\s+")).filter { it.isNotBlank() }.map { RecognizedToken(text = it) })
            }
            override fun onError(error: Int, isCritical: Boolean) {
                Toast.makeText(context, "Recognition error: ${SpeechRecognitionManager.getErrorText(error)}", Toast.LENGTH_SHORT).show()
                viewModel.setRecording(false)
            }
        }

        speechRecognitionManager = SpeechRecognitionManager(context, speechListener, prefs)

        // Check for permission on initial launch
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognitionManager?.destroy()
            speechAligner?.shutdown()
            strictManager?.shutdown()
        }
    }

    // --- UI ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(storyTitle ?: "Story") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { handleRestartAction() }, // Wrapped in a lambda to fix the warning
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
                MicFab(isRecording = isRecording, onClick = { handleRecordAction() }) // Wrapped in a lambda
            }
        }
    ) { innerPadding ->
        StoryContent(
            words = words,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
