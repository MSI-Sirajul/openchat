package com.msi.openchat.feature.calls

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.databinding.FragmentCallBinding
import kotlinx.coroutines.launch

class CallFragment : Fragment() {

    private var _binding: FragmentCallBinding? = null
    private val binding get() = _binding!!

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            callViewModel.onPermissionGranted()
        } else {
            Toast.makeText(
                requireContext(),
                "Microphone permission is required for voice calls",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private var contactId: String = ""
    private var contactName: String = "Voice Call"
    private var callId: String? = null
    private var isIncoming: Boolean = false
    private var isAlreadyAnswered: Boolean = false

    private val callViewModel: CallViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CallViewModel(
                    context = requireContext().applicationContext,
                    initialCallId = callId,
                    contactId = contactId,
                    contactName = contactName,
                    isIncoming = isIncoming,
                    isAlreadyAnswered = isAlreadyAnswered
                ) as T
            }
        }
    }

    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            contactId = it.getString(ARG_CONTACT_ID, "")
            contactName = it.getString(ARG_CONTACT_NAME, "Voice Call")
            callId = it.getString(ARG_CALL_ID)?.takeIf { id -> id.isNotBlank() }
            isIncoming = it.getBoolean(ARG_IS_INCOMING, false)
            isAlreadyAnswered = it.getBoolean(ARG_IS_ALREADY_ANSWERED, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkAndRequestMicPermission()
        setupButtons()
        startPulseAnimation()
        observeViewModel()
    }

    private fun checkAndRequestMicPermission() {
        val hasMic = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupButtons() {
        binding.fabAcceptIncoming.setOnClickListener {
            val hasMic = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                callViewModel.acceptCall()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.fabDeclineIncoming.setOnClickListener {
            callViewModel.declineCall()
        }

        binding.fabEndCall.setOnClickListener {
            callViewModel.endCall()
        }

        binding.fabMuteAudio.setOnClickListener {
            callViewModel.toggleMute()
        }

        binding.fabToggleSpeaker.setOnClickListener {
            callViewModel.toggleSpeaker()
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.ringCallerPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0.2f, 0.7f)
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    callViewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
            }
        }
    }

    private fun renderState(state: CallUiState) {
        binding.textCallerName.text = state.contactName
        binding.textCallerAvatarInitial.text = state.contactName.take(1).uppercase()

        if (state.durationSeconds > 0) {
            val min = state.durationSeconds / 60
            val sec = state.durationSeconds % 60
            binding.textCallStatus.text = "%02d:%02d".format(min, sec)
            binding.textCallStatus.setTextColor(Color.parseColor("#5DD69A"))
        } else {
            binding.textCallStatus.text = state.callStatus
            binding.textCallStatus.setTextColor(Color.parseColor("#A3B1BF"))
        }

        // Control sets
        if (state.isIncomingWaiting) {
            binding.layoutIncomingCallControls.visibility = View.VISIBLE
            binding.layoutActiveCallControls.visibility = View.GONE
        } else {
            binding.layoutIncomingCallControls.visibility = View.GONE
            binding.layoutActiveCallControls.visibility = View.VISIBLE
        }

        // Mute state
        if (state.isMuted) {
            binding.fabMuteAudio.setImageResource(R.drawable.ic_mic_off_24)
            binding.fabMuteAudio.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
        } else {
            binding.fabMuteAudio.setImageResource(R.drawable.ic_mic_24)
            binding.fabMuteAudio.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#344452"))
        }

        // Speaker state
        if (state.isSpeakerOn) {
            binding.fabToggleSpeaker.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#087EBA"))
        } else {
            binding.fabToggleSpeaker.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#344452"))
        }

        // Call ended or inactive
        if (!state.isCallActive) {
            pulseAnimator?.cancel()
            parentFragmentManager.popBackStack()
        }

        // Error message
        state.errorMessage?.let { error ->
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        pulseAnimator = null
        _binding = null
    }

    companion object {
        private const val ARG_CONTACT_ID = "contact_id"
        private const val ARG_CONTACT_NAME = "contact_name"
        private const val ARG_CALL_ID = "call_id"
        private const val ARG_IS_INCOMING = "is_incoming"
        private const val ARG_IS_ALREADY_ANSWERED = "is_already_answered"

        fun newInstance(
            contactId: String,
            contactName: String,
            callId: String? = null,
            isIncoming: Boolean = false,
            isAlreadyAnswered: Boolean = false
        ): CallFragment {
            return CallFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTACT_ID, contactId)
                    putString(ARG_CONTACT_NAME, contactName)
                    putString(ARG_CALL_ID, callId)
                    putBoolean(ARG_IS_INCOMING, isIncoming)
                    putBoolean(ARG_IS_ALREADY_ANSWERED, isAlreadyAnswered)
                }
            }
        }
    }
}
