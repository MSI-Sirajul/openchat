package com.msi.openchat.feature.security

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.MainActivity
import com.example.R
import com.example.databinding.FragmentAppLockBinding
import com.msi.openchat.core.security.BiometricAuthHelper
import kotlinx.coroutines.launch

class AppLockFragment : Fragment() {

    private var _binding: FragmentAppLockBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppLockViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppLockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupKeypad()
        observeState()
        checkBiometricOnLaunch()
    }

    private fun checkBiometricOnLaunch() {
        val state = viewModel.uiState.value
        if (state.isBiometricEnabled && !state.isLockedOut) {
            triggerBiometricPrompt()
        }
    }

    private fun triggerBiometricPrompt() {
        BiometricAuthHelper.promptBiometricAuthentication(
            activity = requireActivity(),
            title = "OpenChat App Lock",
            subtitle = "Authenticate with your fingerprint",
            negativeButtonText = "Use PIN",
            onSuccess = {
                viewModel.onBiometricSuccess()
            },
            onError = { _, _ -> /* Keep keypad available */ },
            onFailed = {
                viewModel.onBiometricFailed()
            }
        )
    }

    private fun setupKeypad() {
        val digitButtons = listOf(
            binding.btnKey0 to '0',
            binding.btnKey1 to '1',
            binding.btnKey2 to '2',
            binding.btnKey3 to '3',
            binding.btnKey4 to '4',
            binding.btnKey5 to '5',
            binding.btnKey6 to '6',
            binding.btnKey7 to '7',
            binding.btnKey8 to '8',
            binding.btnKey9 to '9'
        )

        for ((btn, digit) in digitButtons) {
            btn.setOnClickListener {
                viewModel.onDigitEntered(digit)
            }
        }

        binding.btnKeyBackspace.setOnClickListener {
            viewModel.onBackspace()
        }

        binding.btnKeyBiometric.setOnClickListener {
            triggerBiometricPrompt()
        }

        binding.btnForgotPin.setOnClickListener {
            showResetPasswordDialog()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.isUnlocked) {
                        (activity as? MainActivity)?.navigateToHome()
                        return@collect
                    }

                    // Update 4 dots
                    val pinLength = state.enteredPin.length
                    val activeColor = requireContext().getColor(R.color.color_primary)
                    val inactiveColor = requireContext().getColor(R.color.color_divider)

                    binding.dotPin1.backgroundTintList = android.content.res.ColorStateList.valueOf(if (pinLength >= 1) activeColor else inactiveColor)
                    binding.dotPin2.backgroundTintList = android.content.res.ColorStateList.valueOf(if (pinLength >= 2) activeColor else inactiveColor)
                    binding.dotPin3.backgroundTintList = android.content.res.ColorStateList.valueOf(if (pinLength >= 3) activeColor else inactiveColor)
                    binding.dotPin4.backgroundTintList = android.content.res.ColorStateList.valueOf(if (pinLength >= 4) activeColor else inactiveColor)

                    // Lockout / Error state
                    if (state.isLockedOut) {
                        binding.textLockError.text = state.errorMessage ?: "Too many incorrect attempts. Please reset your PIN."
                        binding.textLockError.visibility = View.VISIBLE
                        setKeypadEnabled(false)
                    } else if (!state.errorMessage.isNullOrBlank()) {
                        binding.textLockError.text = state.errorMessage
                        binding.textLockError.visibility = View.VISIBLE
                        setKeypadEnabled(true)
                    } else {
                        binding.textLockError.visibility = View.GONE
                        setKeypadEnabled(true)
                    }

                    binding.btnKeyBiometric.visibility = if (state.isBiometricEnabled && !state.isLockedOut) View.VISIBLE else View.INVISIBLE
                }
            }
        }
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        binding.tableKeypad.isEnabled = enabled
        for (i in 0 until binding.tableKeypad.childCount) {
            val row = binding.tableKeypad.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                row.getChildAt(j).isEnabled = enabled
            }
        }
    }

    private fun showResetPasswordDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Enter your account password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Reset Passcode")
            .setMessage("Confirm your OpenChat account password to remove the current passcode lock.")
            .setView(input)
            .setPositiveButton("Verify & Unlock") { _, _ ->
                val password = input.text.toString()
                if (password.isNotBlank()) {
                    viewModel.onResetPasswordChanged(password)
                    viewModel.verifyCredentialsForReset()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): AppLockFragment = AppLockFragment()
    }
}
