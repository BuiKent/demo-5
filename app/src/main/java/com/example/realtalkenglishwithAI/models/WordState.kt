package com.example.realtalkenglishwithAI.models

import androidx.compose.ui.graphics.Color

// Data class để đại diện cho trạng thái của một từ
data class WordState(
val index: Int,
val text: String,
val color: Color = Color.Black, // Mặc định là màu đen
val isFocused: Boolean = false
)