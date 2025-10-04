package com.example.realtalkenglishwithAI.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.adapter.RoleplayScenarioAdapter
import com.example.realtalkenglishwithAI.adapter.StoryAdapter
import com.example.realtalkenglishwithAI.databinding.FragmentHomeBinding
import com.example.realtalkenglishwithAI.model.RoleplayScenario
import com.example.realtalkenglishwithAI.viewmodel.StoryViewModel
import com.google.android.material.slider.Slider

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val storyViewModel: StoryViewModel by viewModels()
    private var isNavigating = false // The new, stronger guard

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDifficultySlider()
        setupRoleplayRecyclerView()
        setupStoriesRecyclerView()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Reset the lock when the user returns to this screen
        isNavigating = false
    }

    private fun setupDifficultySlider() {
        val sharedPreferences = requireActivity().getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val selectedDifficulty = sharedPreferences.getInt("DIFFICULTY_LEVEL", 1) // Default to Intermediate (1)

        binding.difficultySlider.value = selectedDifficulty.toFloat()
        updateDifficultyText(selectedDifficulty)

        binding.difficultySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val newDifficulty = value.toInt()
                updateDifficultyText(newDifficulty)
            }
        }

        binding.difficultySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val finalDifficulty = slider.value.toInt()
                with(sharedPreferences.edit()) {
                    putInt("DIFFICULTY_LEVEL", finalDifficulty)
                    apply()
                }
            }
        })
    }

    private fun updateDifficultyText(level: Int) {
        binding.difficultyTextView.text = when (level) {
            0 -> "Beginner"
            2 -> "Advanced"
            else -> "Intermediate"
        }
    }

    private fun setupRoleplayRecyclerView() {
        val scenarios = listOf(
            RoleplayScenario("Greetings", "https://placehold.co/300x400/6200EE/FFFFFF?text=Hi!"),
            RoleplayScenario("At a Cafe", "https://placehold.co/300x400/03DAC5/000000?text=Cafe"),
            RoleplayScenario("At a Home", "https://placehold.co/300x400/3700B3/FFFFFF?text=Home"),
            RoleplayScenario("Travel", "https://placehold.co/300x400/000000/FFFFFF?text=Travel")
        )

        val adapter = RoleplayScenarioAdapter(scenarios)
        binding.roleplayRecyclerView.adapter = adapter
        binding.roleplayRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
    }

    private fun setupStoriesRecyclerView() {
        val storyAdapter = StoryAdapter { story ->
            // The final, robust check for double-click
            if (!isNavigating && findNavController().currentDestination?.id == R.id.homeFragment) {
                isNavigating = true // Lock navigation
                val bundle = bundleOf(
                    "story_content" to story.content,
                    "story_title" to story.title
                )
                findNavController().navigate(R.id.action_homeFragment_to_storyReadingFragment, bundle)
            }
        }

        binding.storiesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = storyAdapter
        }
    }

    private fun observeViewModel() {
        storyViewModel.stories.observe(viewLifecycleOwner) { stories ->
            (binding.storiesRecyclerView.adapter as? StoryAdapter)?.submitList(stories)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
