package com.example.realtalkenglishwithAI.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.model.Story

class StoryAdapter(private val onClick: (Story) -> Unit) :
    ListAdapter<Story, StoryAdapter.StoryViewHolder>(StoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_story, parent, false)
        return StoryViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class StoryViewHolder(itemView: View, private val onClick: (Story) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.story_title)
        private val descriptionTextView: TextView = itemView.findViewById(R.id.story_description)
        private var currentStory: Story? = null

        init {
            itemView.setOnClickListener {
                currentStory?.let { story -> onClick(story) }
            }
        }

        fun bind(story: Story) {
            currentStory = story
            titleTextView.text = story.title
            descriptionTextView.text = story.description
        }
    }
}

class StoryDiffCallback : DiffUtil.ItemCallback<Story>() {
    override fun areItemsTheSame(oldItem: Story, newItem: Story): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Story, newItem: Story): Boolean = oldItem == newItem
}
