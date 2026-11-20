package com.msi.openchat.feature.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.R
import com.example.databinding.FragmentGeminiAssistantBinding
import kotlinx.coroutines.launch

class GeminiAssistantFragment : Fragment() {

    private var _binding: FragmentGeminiAssistantBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GeminiAssistantViewModel by viewModels()
    private lateinit var adapter: GeminiMessageAdapter

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.attachImage(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeminiAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInsets()
        setupToolbar()
        setupRecyclerView()
        setupSuggestions()
        setupComposer()
        observeState()

        val initialPrompt = arguments?.getString(ARG_INITIAL_PROMPT)?.trim()
        if (!initialPrompt.isNullOrBlank()) {
            arguments?.remove(ARG_INITIAL_PROMPT)
            binding.etGeminiPrompt.setText(initialPrompt)
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutGeminiComposer) { v, insets ->
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars()
            ).bottom
            val basePad = (6 * resources.displayMetrics.density).toInt()
            v.setPadding(v.paddingLeft, basePad, v.paddingRight, basePad + bottom)
            insets
        }
    }

    private fun setupToolbar() {
        binding.toolbarGemini.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnGeminiReset.setOnClickListener {
            viewModel.resetConversation()
        }
    }

    private fun setupRecyclerView() {
        adapter = GeminiMessageAdapter()
        binding.recyclerGeminiMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = this@GeminiAssistantFragment.adapter
        }
    }

    private fun setupSuggestions() {
        val fillPrompt: (String) -> Unit = { prompt ->
            binding.etGeminiPrompt.setText(prompt)
            binding.etGeminiPrompt.setSelection(prompt.length)
            binding.etGeminiPrompt.requestFocus()
        }
        binding.chipSuggestDraft.setOnClickListener {
            fillPrompt("Help me draft a polite and friendly message saying I'll be 10 minutes late.")
        }
        binding.chipSuggestTranslate.setOnClickListener {
            fillPrompt("Translate this to Spanish: Looking forward to catching up later today!")
        }
        binding.chipSuggestSummarize.setOnClickListener {
            fillPrompt("Summarize these meeting notes into 3 quick bullet points:\n")
        }
        binding.chipSuggestBrainstorm.setOnClickListener {
            fillPrompt("Give me 4 creative weekend hangout ideas to suggest in a group chat.")
        }
    }

    private fun setupComposer() {
        binding.btnGeminiAttachImage.setOnClickListener {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnGeminiRemoveImage.setOnClickListener {
            viewModel.clearAttachedImage()
        }
        binding.btnGeminiSend.setOnClickListener {
            submitPrompt()
        }
        binding.etGeminiPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt()
                true
            } else {
                false
            }
        }
        binding.btnGeminiRetry.setOnClickListener {
            val failedPrompt = viewModel.screenState.value.lastFailedPrompt
            if (!failedPrompt.isNullOrBlank()) {
                viewModel.clearError()
                viewModel.sendPrompt(requireContext(), failedPrompt)
            } else {
                viewModel.clearError()
            }
        }
    }

    private fun submitPrompt() {
        val text = binding.etGeminiPrompt.text?.toString().orEmpty()
        if (text.isBlank() && viewModel.screenState.value.selectedImageUri == null) return
        binding.etGeminiPrompt.setText("")
        viewModel.sendPrompt(requireContext(), text)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.screenState.collect { state ->
                    val hasMessages = state.messages.isNotEmpty()
                    binding.layoutGeminiEmpty.visibility = if (hasMessages) View.GONE else View.VISIBLE
                    binding.recyclerGeminiMessages.visibility = if (hasMessages) View.VISIBLE else View.GONE

                    adapter.submitList(state.messages) {
                        if (state.messages.isNotEmpty()) {
                            binding.recyclerGeminiMessages.scrollToPosition(state.messages.size - 1)
                        }
                    }

                    val pendingUri = state.selectedImageUri
                    if (pendingUri != null) {
                        binding.layoutGeminiImagePreview.visibility = View.VISIBLE
                        binding.imgGeminiPending.load(pendingUri) { crossfade(true) }
                    } else {
                        binding.layoutGeminiImagePreview.visibility = View.GONE
                    }

                    val errorState = state.inferenceState as? GeminiUiState.Error
                    if (errorState != null) {
                        binding.cardGeminiError.visibility = View.VISIBLE
                        binding.textGeminiError.text = errorState.message
                    } else {
                        binding.cardGeminiError.visibility = View.GONE
                    }

                    val isBusy = state.inferenceState is GeminiUiState.Loading ||
                        state.inferenceState is GeminiUiState.Streaming
                    binding.btnGeminiSend.isEnabled = !isBusy
                    binding.btnGeminiSend.alpha = if (isBusy) 0.55f else 1.0f
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_INITIAL_PROMPT = "initial_prompt"

        fun newInstance(initialPrompt: String? = null): GeminiAssistantFragment {
            return GeminiAssistantFragment().apply {
                arguments = Bundle().apply {
                    if (!initialPrompt.isNullOrBlank()) {
                        putString(ARG_INITIAL_PROMPT, initialPrompt)
                    }
                }
            }
        }
    }
}
