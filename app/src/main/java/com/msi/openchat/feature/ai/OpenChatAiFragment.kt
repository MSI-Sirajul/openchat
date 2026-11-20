package com.msi.openchat.feature.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.R
import com.example.databinding.FragmentOpenchatAiBinding
import kotlinx.coroutines.launch

class OpenChatAiFragment : Fragment() {

    private var _binding: FragmentOpenchatAiBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OpenChatAiViewModel by viewModels()
    private lateinit var adapter: OpenChatAiMessageAdapter

    private var isImeVisible = false
    private var wasNearBottomOnImePrepare = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOpenchatAiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.syncStatusBarColor()
        setupKeyboardTracking()
        setupToolbar()
        setupRecyclerView()
        setupSuggestions()
        setupComposer()
        observeState()

        val initialPrompt = arguments?.getString(ARG_INITIAL_PROMPT)?.trim()
        if (!initialPrompt.isNullOrBlank()) {
            arguments?.remove(ARG_INITIAL_PROMPT)
            binding.etOpenchatAiPrompt.setText(initialPrompt)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.syncStatusBarColor()
    }

    private fun setupKeyboardTracking() {
        val density = resources.displayMetrics.density

        fun updateComposerBottomInset(animatedBottomOverride: Int? = null) {
            val currentBinding = _binding ?: return
            val root = currentBinding.layoutOpenchatAiRoot
            val rootInsets = ViewCompat.getRootWindowInsets(root)
            val imeInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())
            val navInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())

            val imeBottom = animatedBottomOverride ?: (imeInsets?.bottom ?: 0)
            val navBottom = navInsets?.bottom ?: 0
            val imeVisibleNow = imeBottom > navBottom ||
                (rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true && imeBottom > 0)

            val decorHeight = activity?.window?.decorView?.height ?: 0
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            val fragmentBottomInWindow = loc[1] + root.height
            val alreadyInsetByParent = if (decorHeight > 0 && root.height > 0) {
                (decorHeight - fragmentBottomInWindow).coerceAtLeast(0)
            } else {
                0
            }

            val targetBottom = maxOf(imeBottom, navBottom)
            val unconsumedBottom = (targetBottom - alreadyInsetByParent).coerceAtLeast(0)
            if (binding.layoutOpenchatAiComposer.paddingBottom != unconsumedBottom) {
                binding.layoutOpenchatAiComposer.setPadding(0, 0, 0, unconsumedBottom)
            }

            if (imeVisibleNow != isImeVisible) {
                isImeVisible = imeVisibleNow
                updateEmptyStateDensity(imeVisibleNow, density)
                if (imeVisibleNow && adapter.itemCount > 0) {
                    binding.recyclerOpenchatAiMessages.post { scrollToLatestMessage() }
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutOpenchatAiRoot) { _, insets ->
            binding.layoutOpenchatAiRoot.post {
                if (_binding != null) updateComposerBottomInset()
            }
            insets
        }

        // Smooth frame-by-frame IME animation tracking so messages & composer stay locked together.
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.layoutOpenchatAiRoot,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        wasNearBottomOnImePrepare = isMessagesNearBottom()
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val hasImeAnim = runningAnimations.any { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }
                    if (hasImeAnim) {
                        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        updateComposerBottomInset(animatedBottomOverride = imeBottom)
                        if (wasNearBottomOnImePrepare && adapter.itemCount > 0) {
                            scrollToLatestMessage()
                        }
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        updateComposerBottomInset()
                        if (wasNearBottomOnImePrepare && adapter.itemCount > 0) {
                            binding.recyclerOpenchatAiMessages.post { scrollToLatestMessage() }
                        }
                    }
                }
            }
        )

        // Sync after parent CoordinatorLayout/adjustResize finishes laying out fragment bounds.
        binding.layoutOpenchatAiRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateComposerBottomInset()
        }

        // Keep latest message visible whenever keyboard or multi-line composer shrinks the list height.
        binding.recyclerOpenchatAiMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && adapter.itemCount > 0 && isMessagesNearBottom(threshold = 2)) {
                binding.recyclerOpenchatAiMessages.post { scrollToLatestMessage() }
            }
        }
    }

    private fun updateEmptyStateDensity(imeVisible: Boolean, density: Float) {
        val outerPadV = ((if (imeVisible) 10 else 20) * density).toInt()
        val outerPadH = (18 * density).toInt()
        binding.layoutOpenchatAiEmptyContent.setPadding(outerPadH, outerPadV, outerPadH, outerPadV)

        val heroPadV = ((if (imeVisible) 14 else 22) * density).toInt()
        val heroPadH = (20 * density).toInt()
        binding.layoutOpenchatAiHero.setPadding(heroPadH, heroPadV, heroPadH, heroPadV)
        binding.textOpenchatAiEmptyBody.visibility = if (imeVisible) View.GONE else View.VISIBLE
    }

    private fun isMessagesNearBottom(threshold: Int = 1): Boolean {
        val count = adapter.itemCount
        if (count <= 0) return true
        val lm = binding.recyclerOpenchatAiMessages.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = lm.findLastVisibleItemPosition()
        return lastVisible == RecyclerViewNoPosition || lastVisible >= count - 1 - threshold
    }

    private fun scrollToLatestMessage() {
        val count = adapter.itemCount
        if (count > 0) {
            binding.recyclerOpenchatAiMessages.scrollToPosition(count - 1)
        }
    }

    private fun setupToolbar() {
        binding.toolbarOpenchatAi.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnOpenchatAiModelToggle.setOnClickListener {
            viewModel.toggleModel()
        }
        binding.btnOpenchatAiReset.setOnClickListener {
            viewModel.resetConversation()
        }
    }

    private fun setupRecyclerView() {
        adapter = OpenChatAiMessageAdapter { responseText ->
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.openchat_ai_title), responseText))
            Toast.makeText(requireContext(), getString(R.string.openchat_ai_response_copied), Toast.LENGTH_SHORT).show()
        }
        binding.recyclerOpenchatAiMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = this@OpenChatAiFragment.adapter
        }
    }

    private fun setupSuggestions() {
        val fillPrompt: (String) -> Unit = { prompt ->
            binding.etOpenchatAiPrompt.setText(prompt)
            binding.etOpenchatAiPrompt.setSelection(prompt.length)
            binding.etOpenchatAiPrompt.requestFocus()
            activity?.window?.let { win ->
                WindowInsetsControllerCompat(win, binding.etOpenchatAiPrompt).show(WindowInsetsCompat.Type.ime())
            }
        }
        binding.cardSuggestDraft.setOnClickListener {
            fillPrompt("Help me draft a polite and friendly message saying I'll be 10 minutes late.")
        }
        binding.cardSuggestTranslate.setOnClickListener {
            fillPrompt("Translate this to Spanish: Looking forward to catching up later today!")
        }
        binding.cardSuggestSummarize.setOnClickListener {
            fillPrompt("Summarize these meeting notes into 3 quick bullet points:\n")
        }
        binding.cardSuggestBrainstorm.setOnClickListener {
            fillPrompt("Give me 4 creative weekend hangout ideas to suggest in a group chat.")
        }
    }

    private fun setupComposer() {
        binding.btnOpenchatAiSend.setOnClickListener {
            submitPrompt()
        }
        binding.etOpenchatAiPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt()
                true
            } else {
                false
            }
        }
        binding.btnOpenchatAiRetry.setOnClickListener {
            val failedPrompt = viewModel.screenState.value.lastFailedPrompt
            if (!failedPrompt.isNullOrBlank()) {
                viewModel.clearError()
                viewModel.sendPrompt(failedPrompt)
            } else {
                viewModel.clearError()
            }
        }
    }

    private fun submitPrompt() {
        val text = binding.etOpenchatAiPrompt.text?.toString().orEmpty()
        if (text.isBlank()) return
        binding.etOpenchatAiPrompt.setText("")
        viewModel.sendPrompt(text)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.screenState.collect { state ->
                    val hasMessages = state.messages.isNotEmpty()
                    binding.layoutOpenchatAiEmpty.visibility = if (hasMessages) View.GONE else View.VISIBLE
                    binding.recyclerOpenchatAiMessages.visibility = if (hasMessages) View.VISIBLE else View.GONE

                    val isReasoner = state.selectedModel == DeepSeekModelOption.REASONER
                    binding.btnOpenchatAiModelToggle.text = getString(
                        if (isReasoner) R.string.openchat_ai_model_r1 else R.string.openchat_ai_model_chat
                    )
                    binding.textOpenchatAiSubtitle.text = getString(
                        if (isReasoner) R.string.openchat_ai_subtitle_r1 else R.string.openchat_ai_subtitle_chat
                    )

                    adapter.submitList(state.messages) {
                        if (state.messages.isNotEmpty()) {
                            scrollToLatestMessage()
                        }
                    }

                    val errorState = state.inferenceState as? OpenChatAiUiState.Error
                    if (errorState != null) {
                        binding.cardOpenchatAiError.visibility = View.VISIBLE
                        binding.textOpenchatAiError.text = errorState.message
                    } else {
                        binding.cardOpenchatAiError.visibility = View.GONE
                    }

                    val isBusy = state.inferenceState is OpenChatAiUiState.Loading ||
                        state.inferenceState is OpenChatAiUiState.Streaming
                    binding.btnOpenchatAiSend.isEnabled = !isBusy
                    binding.btnOpenchatAiSend.alpha = if (isBusy) 0.55f else 1.0f
                }
            }
        }
    }

    override fun onDestroyView() {
        ViewCompat.setWindowInsetsAnimationCallback(binding.layoutOpenchatAiRoot, null)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val RecyclerViewNoPosition = -1
        private const val ARG_INITIAL_PROMPT = "initial_prompt"

        fun newInstance(initialPrompt: String? = null): OpenChatAiFragment {
            return OpenChatAiFragment().apply {
                arguments = Bundle().apply {
                    if (!initialPrompt.isNullOrBlank()) {
                        putString(ARG_INITIAL_PROMPT, initialPrompt)
                    }
                }
            }
        }
    }
}
