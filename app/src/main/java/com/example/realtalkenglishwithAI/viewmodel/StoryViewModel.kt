package com.example.realtalkenglishwithAI.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.model.Story
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class StoryViewModel(application: Application) : AndroidViewModel(application) {
    private val _stories = MutableLiveData<List<Story>>()
    val stories: LiveData<List<Story>> = _stories

    init {
        loadStories()
    }

    fun loadStories() {
        viewModelScope.launch {
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    val inputStream: InputStream = getApplication<Application>().resources.openRawResource(R.raw.stories)
                    inputStream.bufferedReader().use { it.readText() }
                }
                val listType = object : TypeToken<List<Story>>() {}.type
                val storyList: List<Story> = Gson().fromJson(jsonString, listType)
                _stories.postValue(storyList)
            } catch (e: Exception) {
                Log.e("StoryViewModel", "Error loading stories", e)
                _stories.postValue(emptyList())
            }
        }
    }
}
