package com.example.realtalkenglishwithAI.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.example.realtalkenglishwithAI.features.story_reading.ui.StoryReadingScreen

/**
 * A Fragment that hosts the StoryReadingScreen Composable.
 * This acts as a bridge between the Fragment-based Navigation graph and the Composable screen.
 */
class StoryReadingComposeFragment : Fragment() {

    // Use the navArgs delegate to easily access arguments passed from the navigation graph
    private val args: StoryReadingComposeFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            // Set the content of this ComposeView to our StoryReadingScreen
            setContent {
                StoryReadingScreen(
                    storyTitle = args.storyTitle,
                    storyContent = args.storyContent
                )
            }
        }
    }
}
