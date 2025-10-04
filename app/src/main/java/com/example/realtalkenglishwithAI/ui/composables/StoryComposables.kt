package com.example.realtalkenglishwithAI.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
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
    FloatingActionButton(onClick = onClick) {
        val iconRes = if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = if (isRecording) "Stop recording" else "Start recording"
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
        WordState(5, "a", Color.Black),
        WordState(6, "faraway", Color.Black),
        WordState(7, "land", Color.Black)
    )
    StoryContent(words = sampleWords)
}

@Preview
@Composable
fun FullScreenPreview() {
    Scaffold(
        floatingActionButton = {
            MicFab(isRecording = false) {}
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            val sampleWords = listOf(
                WordState(0, "Once", Color.Gray),
                WordState(1, "upon", Color.Green),
                WordState(2, "a", Color.Red),
                WordState(3, "time", Color.Black, isFocused = true),
                WordState(4, "in", Color.Black),
                WordState(5, "a", Color.Black),
                WordState(6, "faraway", Color.Black),
                WordState(7, "land", Color.Black)
            )
            StoryContent(words = sampleWords)
        }
    }
}