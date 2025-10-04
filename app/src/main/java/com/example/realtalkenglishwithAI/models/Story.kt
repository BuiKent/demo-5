package com.example.realtalkenglishwithAI.models

import com.google.gson.annotations.SerializedName

/**
 * Data class representing a single story from the stories.json file.
 */
data class Story(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("description")
    val description: String,

    @SerializedName("content")
    val content: String
)
