package com.example.realtalkenglishwithAI.ui.composables

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.models.WordState

@Composable
fun MicFab(isRecording: Boolean, onClick: () -> Unit) {
    val containerColor = if (isRecording) {
        colorResource(id = R.color.word_incorrect)
    } else {
        Color.White
    }
    val iconTint = if (isRecording) Color.White else Color.Black

    FloatingActionButton(
        onClick = onClick,
        modifier = if (isRecording) {
            Modifier
        } else {
            Modifier.border(1.dp, Color.Black, CircleShape)
        },
        containerColor = containerColor,
        shape = CircleShape // Force the shape to be a perfect circle
    ) {
        // Per user request, the icon is always Mic, only the color changes.
        val iconVector = Icons.Filled.Mic
        Icon(
            imageVector = iconVector,
            contentDescription = if (isRecording) "Stop recording" else "Start recording",
            tint = iconTint
        )
    }
}

@Composable
fun StoryContent(words: List<WordState>, modifier: Modifier = Modifier) {
    val focusBackgroundColor = colorResource(id = R.color.word_focus_background)

    val annotatedString = buildAnnotatedString {
        words.forEach { word ->
            withStyle(
                style = SpanStyle(
                    color = word.color,
                    background = if (word.isFocused) focusBackgroundColor else Color.Transparent
                )
            ) {
                append(word.text)
            }
            append(" ")
        }
    }

    Text(
        text = annotatedString,
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
        fontSize = 20.sp,
        lineHeight = 32.sp
    )
}

// --- PREVIEWS ---
@Preview(showBackground = true)
@Composable
fun MicFabPreview_NotRecording() {
    MicFab(isRecording = false, onClick = {})
}

@Preview(showBackground = true)
@Composable
fun MicFabPreview_Recording() {
    MicFab(isRecording = true, onClick = {})
}

@Preview(showBackground = true)
@Composable
fun StoryContentPreview() {
    val sampleWords = listOf(
        WordState(0, "Once", Color.Gray),
        WordState(1, "upon", Color.Green),
        WordState(2, "a", Color.Red),
        WordState(3, "time", Color.Black, isFocused = true),
        WordState(4, "in", Color.Black),

    )
    StoryContent(words = sampleWords)
}

@Preview
@Composable
fun FullScreenPreview() {
    // --- STATE MANAGEMENT FOR PREVIEW LOGIC ---
    var isRecording by remember { mutableStateOf(false) }
    var focusedWordIndex by remember { mutableIntStateOf(3) } // Track the current word

    // --- DYNAMICALLY GENERATED WORD LIST ---
    val originalWords = listOf("Once", "upon", "a", "time", "in", "a", "faraway", "land")
    val sampleWords = originalWords.mapIndexed { index, text ->
        WordState(
            index = index, // CORRECTED: Parameter name is 'index', not 'id'
            text = text,
            color = when {
                index < focusedWordIndex -> Color.Gray
                else -> Color.Black
            },
            isFocused = index == focusedWordIndex
        )
    }

    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // --- RESTART BUTTON LOGIC ---
                FloatingActionButton(
                    onClick = {
                        focusedWordIndex = 0      // Reset position to the beginning
                        isRecording = true        // Start recording immediately
                    },
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

                // --- MIC BUTTON LOGIC ---
                MicFab(
                    isRecording = isRecording,
                    onClick = { isRecording = !isRecording } // Toggles recording on/off
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            StoryContent(words = sampleWords)
        }
    }
}
