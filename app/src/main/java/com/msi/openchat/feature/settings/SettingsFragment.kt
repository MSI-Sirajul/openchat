package com.msi.openchat.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.MainActivity
import com.example.R
import com.example.databinding.DialogEditProfileBinding
import com.example.databinding.DialogSetPinBinding
import com.example.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.msi.openchat.core.theme.AppThemeMode
import com.msi.openchat.data.local.OfflineMediaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarSettings.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupProfileSection()
        setupThemeSection()
        setupSecuritySection()
        setupStorageSection()
        setupLogoutSection()
        observeViewModel()
    }

    private fun setupProfileSection() {
        binding.btnEditProfile.setOnClickListener {
            val state = settingsViewModel.uiState.value
            val dialogBinding = DialogEditProfileBinding.inflate(layoutInflater)
            dialogBinding.etEditDisplayName.setText(state.displayName)
            dialogBinding.etEditBio.setText(state.bio)

            MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.root)
                .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                    val newName = dialogBinding.etEditDisplayName.text?.toString().orEmpty().trim()
                    val newBio = dialogBinding.etEditBio.text?.toString().orEmpty().trim()
                    if (newName.isNotBlank()) {
                        settingsViewModel.onDisplayNameChanged(newName)
                        settingsViewModel.onBioChanged(newBio)
                        settingsViewModel.saveProfile()
                    }
                }
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show()
        }
    }

    private var isUpdatingThemeRadio = false

    private fun setupThemeSection() {
        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingThemeRadio) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.radio_theme_light -> AppThemeMode.LIGHT
                R.id.radio_theme_dark -> AppThemeMode.DARK
                else -> AppThemeMode.SYSTEM
            }
            if (settingsViewModel.uiState.value.currentThemeMode != mode) {
                settingsViewModel.selectThemeMode(mode, activity)
            }
        }
    }

    private fun setupSecuritySection() {
        binding.switchAppLock.setOnClickListener {
            val isChecked = binding.switchAppLock.isChecked
            if (isChecked) {
                showSetPinDialog()
            } else {
                settingsViewModel.onToggleAppLock(false)
            }
        }

        binding.btnChangePin.setOnClickListener {
            showSetPinDialog()
        }

        binding.switchBiometric.setOnClickListener {
            val isChecked = binding.switchBiometric.isChecked
            if (isChecked) {
                val status = com.msi.openchat.core.security.BiometricAuthHelper.getBiometricStatus(requireContext())
                if (status != com.msi.openchat.core.security.BiometricAuthHelper.BiometricStatus.AVAILABLE) {
                    binding.switchBiometric.isChecked = false
                    Toast.makeText(
                        requireContext(),
                        com.msi.openchat.core.security.BiometricAuthHelper.getBiometricStatusMessage(status),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                if (!settingsViewModel.uiState.value.isAppLockEnabled) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.settings_backup_pin_title))
                        .setMessage(getString(R.string.settings_backup_pin_message))
                        .setPositiveButton(getString(R.string.settings_set_pin_button)) { _, _ ->
                            showSetPinDialog(onSuccess = {
                                verifyAndEnableBiometric()
                            })
                        }
                        .setNegativeButton(getString(R.string.common_cancel)) { _, _ ->
                            binding.switchBiometric.isChecked = false
                        }
                        .show()
                } else {
                    verifyAndEnableBiometric()
                }
            } else {
                settingsViewModel.onToggleBiometric(false)
            }
        }
    }

    private fun verifyAndEnableBiometric() {
        com.msi.openchat.core.security.BiometricAuthHelper.promptBiometricAuthentication(
            activity = requireActivity(),
            title = getString(R.string.settings_verify_fingerprint_title),
            subtitle = getString(R.string.settings_verify_fingerprint_subtitle),
            negativeButtonText = getString(R.string.common_cancel),
            onSuccess = {
                settingsViewModel.onToggleBiometric(true)
                Toast.makeText(requireContext(), getString(R.string.settings_fingerprint_enabled_toast), Toast.LENGTH_SHORT).show()
            },
            onError = { _, errString ->
                binding.switchBiometric.isChecked = false
                Toast.makeText(requireContext(), getString(R.string.settings_biometric_verification_error, errString), Toast.LENGTH_SHORT).show()
            },
            onFailed = {
                binding.switchBiometric.isChecked = false
                Toast.makeText(requireContext(), getString(R.string.settings_fingerprint_not_recognized), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showSetPinDialog(onSuccess: (() -> Unit)? = null) {
        val dialogBinding = DialogSetPinBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.settings_set_pin_button), null)
            .setNegativeButton(getString(R.string.common_cancel)) { _, _ ->
                binding.switchAppLock.isChecked = settingsViewModel.uiState.value.isAppLockEnabled
            }
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val pin = dialogBinding.etNewPin.text?.toString().orEmpty()
                val confirmPin = dialogBinding.etConfirmPin.text?.toString().orEmpty()

                if (pin.length != 4) {
                    dialogBinding.tilNewPin.error = getString(R.string.settings_pin_length_short_error)
                    return@setOnClickListener
                }
                if (pin != confirmPin) {
                    dialogBinding.tilConfirmPin.error = getString(R.string.settings_pin_mismatch_error)
                    return@setOnClickListener
                }

                settingsViewModel.onPinInputChanged(pin)
                settingsViewModel.onConfirmPinInputChanged(confirmPin)
                settingsViewModel.savePin()
                dialog.dismiss()
                onSuccess?.invoke()
            }
        }
        dialog.show()
    }

    private fun setupStorageSection() {
        updateCacheSizeDisplay()

        binding.btnClearCache.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    requireContext().cacheDir?.deleteRecursively()
                    val mediaDir = OfflineMediaManager.getMediaDirectory(requireContext())
                    mediaDir.listFiles()?.forEach { file -> file.delete() }
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show()
                    updateCacheSizeDisplay()
                }
            }
        }
    }

    private fun updateCacheSizeDisplay() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var totalBytes = 0L
            try {
                fun getDirSize(dir: File?): Long {
                    if (dir == null || !dir.exists()) return 0L
                    var size = 0L
                    dir.listFiles()?.forEach { file ->
                        size += if (file.isDirectory) getDirSize(file) else file.length()
                    }
                    return size
                }
                totalBytes += getDirSize(requireContext().cacheDir)
                totalBytes += getDirSize(OfflineMediaManager.getMediaDirectory(requireContext()))
            } catch (_: Exception) {}

            val mb = totalBytes / (1024.0 * 1024.0)
            withContext(Dispatchers.Main) {
                _binding?.textCacheSize?.text = getString(R.string.common_storage_used_mb, mb)
            }
        }
    }

    private fun setupLogoutSection() {
        binding.btnSettingsLogout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.settings_logout))
                .setMessage(getString(R.string.settings_logout_confirm))
                .setPositiveButton(getString(R.string.settings_logout)) { _, _ ->
                    settingsViewModel.logout()
                }
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    settingsViewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
            }
        }
    }

    private fun renderState(state: SettingsUiState) {
        val displayName = state.displayName.ifBlank { state.username.ifBlank { getString(R.string.common_user) } }
        binding.textSettingsName.text = displayName
        com.msi.openchat.core.ui.AvatarHelper.loadAvatar(
            imageView = binding.imgSettingsAvatar,
            letterTextView = binding.textSettingsAvatar,
            avatarUrl = state.avatarUrl,
            displayName = displayName
        )
        binding.textSettingsUsername.text = if (state.username.isNotBlank()) getString(R.string.common_handle_format, state.username) else ""

        // Theme selection radio
        isUpdatingThemeRadio = true
        when (state.currentThemeMode) {
            AppThemeMode.LIGHT -> binding.radioThemeLight.isChecked = true
            AppThemeMode.DARK -> binding.radioThemeDark.isChecked = true
            AppThemeMode.SYSTEM -> binding.radioThemeSystem.isChecked = true
        }
        isUpdatingThemeRadio = false

        // App Lock switch state
        binding.switchAppLock.isChecked = state.isAppLockEnabled
        binding.btnChangePin.visibility = if (state.isAppLockEnabled) View.VISIBLE else View.GONE
        binding.layoutBiometricToggle.visibility = View.VISIBLE
        binding.switchBiometric.isChecked = state.isBiometricEnabled

        state.message?.let { msg ->
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        if (state.isLoggedOut) {
            (activity as? MainActivity)?.navigateToAuth()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): SettingsFragment = SettingsFragment()
    }
}
