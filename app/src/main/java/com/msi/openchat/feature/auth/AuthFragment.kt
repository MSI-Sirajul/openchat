package com.msi.openchat.feature.auth

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.MainActivity
import com.example.R
import com.example.databinding.FragmentAuthBinding
import com.msi.openchat.core.permission.PermissionManager
import kotlinx.coroutines.launch

class AuthFragment : Fragment() {

    private var _binding: FragmentAuthBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeState()
    }

    private fun setupListeners() {
        binding.btnTabSignin.setOnClickListener {
            if (!viewModel.uiState.value.isLogin) {
                viewModel.toggleAuthMode()
            }
        }

        binding.btnTabSignup.setOnClickListener {
            if (viewModel.uiState.value.isLogin) {
                viewModel.toggleAuthMode()
            }
        }

        binding.btnToggleAuthMode.setOnClickListener {
            viewModel.toggleAuthMode()
        }

        binding.btnAuthSubmit.setOnClickListener {
            viewModel.submit()
        }

        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        binding.cbRememberMe.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onRememberMeChanged(isChecked)
        }

        // Text Watchers
        binding.etIdentifier.addTextChangedListener(object : SimpleTextWatcher() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                viewModel.onEmailChanged(text)
            }
        })

        binding.etDisplayName.addTextChangedListener(object : SimpleTextWatcher() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onDisplayNameChanged(s?.toString().orEmpty())
            }
        })

        binding.etUsername.addTextChangedListener(object : SimpleTextWatcher() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onUsernameChanged(s?.toString().orEmpty())
            }
        })

        binding.etEmail.addTextChangedListener(object : SimpleTextWatcher() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onEmailChanged(s?.toString().orEmpty())
            }
        })

        binding.etPhone.addTextChangedListener(object : SimpleTextWatcher() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onPhoneNumberChanged(s?.toString().orEmpty())
            }
        })

        binding.etPassword.addTextChangedListener(object : SimpleTextWatcher() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onPasswordChanged(s?.toString().orEmpty())
            }
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.isAuthenticated) {
                        if (PermissionManager.shouldShowOnboarding(requireContext())) {
                            (activity as? MainActivity)?.navigateToPermissionOnboarding()
                        } else {
                            (activity as? MainActivity)?.navigateToHome()
                        }
                        return@collect
                    }

                    // Mode: Sign In vs Sign Up
                    val primaryColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.color_primary)
                    val secondaryTextColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.color_secondary_text)

                    if (state.isLogin) {
                        binding.btnTabSignin.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                        binding.btnTabSignin.setTextColor(android.graphics.Color.WHITE)
                        binding.btnTabSignup.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                        binding.btnTabSignup.setTextColor(secondaryTextColor)

                        binding.textAuthHeader.text = getString(R.string.auth_welcome_back)
                        binding.btnAuthSubmit.text = getString(R.string.auth_sign_in_button)
                        binding.btnToggleAuthMode.text = getString(R.string.auth_dont_have_account)

                        binding.tilIdentifier.visibility = View.VISIBLE
                        binding.tilDisplayName.visibility = View.GONE
                        binding.tilUsername.visibility = View.GONE
                        binding.tilEmail.visibility = View.GONE
                        binding.tilPhone.visibility = View.GONE
                        binding.layoutAuthOptions.visibility = View.VISIBLE
                    } else {
                        binding.btnTabSignup.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                        binding.btnTabSignup.setTextColor(android.graphics.Color.WHITE)
                        binding.btnTabSignin.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                        binding.btnTabSignin.setTextColor(secondaryTextColor)

                        binding.textAuthHeader.text = getString(R.string.auth_create_account)
                        binding.btnAuthSubmit.text = getString(R.string.auth_sign_up_button)
                        binding.btnToggleAuthMode.text = getString(R.string.auth_already_have_account)

                        binding.tilIdentifier.visibility = View.GONE
                        binding.tilDisplayName.visibility = View.VISIBLE
                        binding.tilUsername.visibility = View.VISIBLE
                        binding.tilEmail.visibility = View.VISIBLE
                        binding.tilPhone.visibility = View.VISIBLE
                        binding.layoutAuthOptions.visibility = View.GONE
                    }

                    // Loading State
                    binding.progressAuth.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.btnAuthSubmit.isEnabled = !state.isLoading

                    // Error & Success Feedback
                    if (!state.errorMessage.isNullOrBlank()) {
                        binding.cardAuthError.visibility = View.VISIBLE
                        binding.textAuthError.text = state.errorMessage
                    } else {
                        binding.cardAuthError.visibility = View.GONE
                    }

                    if (!state.successMessage.isNullOrBlank()) {
                        binding.cardAuthSuccess.visibility = View.VISIBLE
                        binding.textAuthSuccess.text = state.successMessage
                    } else {
                        binding.cardAuthSuccess.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun showForgotPasswordDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Enter your registered email"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.auth_forgot_password)
            .setMessage("We will send a password reset link to your email address.")
            .setView(input)
            .setPositiveButton("Send Reset Link") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isNotBlank()) {
                    viewModel.sendPasswordReset(email)
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
        fun newInstance(): AuthFragment = AuthFragment()
    }

    open class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {}
    }
}
