package com.example.realtalkenglishwithAI.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.example.realtalkenglishwithAI.models.WordState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StoryReadingViewModel : ViewModel() {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun setRecording(on: Boolean) {
        _isRecording.value = on
    }

    private val _words = MutableStateFlow<List<WordState>>(emptyList())
    val words: StateFlow<List<WordState>> = _words.asStateFlow()

    fun setWords(storyText: String) {
        val wordList = storyText.split(Regex("\\s+")).mapIndexed { index, text ->
            WordState(index = index, text = text)
        }
        _words.value = wordList
    }

    fun updateWordColor(index: Int, color: Color) {
        _words.update { currentWords ->
            if (index < 0 || index >= currentWords.size) return@update currentWords
            currentWords.map {
                if (it.index == index) it.copy(color = color) else it
            }
        }
    }

    fun setWordFocus(index: Int) {
        _words.update { currentWords ->
            currentWords.map {
                it.copy(isFocused = it.index == index)
            }
        }
    }
}
