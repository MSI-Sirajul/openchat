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
import com.msi.openchat.core.di.ServiceLocator
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
                .setPositiveButton("Save") { _, _ ->
                    val newName = dialogBinding.etEditDisplayName.text?.toString().orEmpty().trim()
                    val newBio = dialogBinding.etEditBio.text?.toString().orEmpty().trim()
                    if (newName.isNotBlank()) {
                        settingsViewModel.onDisplayNameChanged(newName)
                        settingsViewModel.onBioChanged(newBio)
                        settingsViewModel.saveProfile()
                    }
                }
                .setNegativeButton("Cancel", null)
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
        binding.switchAppLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != settingsViewModel.uiState.value.isAppLockEnabled) {
                if (isChecked) {
                    showSetPinDialog()
                } else {
                    settingsViewModel.onToggleAppLock(false)
                }
            }
        }

        binding.btnChangePin.setOnClickListener {
            showSetPinDialog()
        }

        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != settingsViewModel.uiState.value.isBiometricEnabled) {
                settingsViewModel.onToggleBiometric(isChecked)
            }
        }
    }

    private fun showSetPinDialog() {
        val dialogBinding = DialogSetPinBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton("Set PIN", null)
            .setNegativeButton("Cancel") { _, _ ->
                binding.switchAppLock.isChecked = settingsViewModel.uiState.value.isAppLockEnabled
            }
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val pin = dialogBinding.etNewPin.text?.toString().orEmpty()
                val confirmPin = dialogBinding.etConfirmPin.text?.toString().orEmpty()

                if (pin.length != 4) {
                    dialogBinding.tilNewPin.error = "PIN must be 4 digits"
                    return@setOnClickListener
                }
                if (pin != confirmPin) {
                    dialogBinding.tilConfirmPin.error = "PINs do not match"
                    return@setOnClickListener
                }

                settingsViewModel.onPinInputChanged(pin)
                settingsViewModel.onConfirmPinInputChanged(confirmPin)
                settingsViewModel.savePin()
                dialog.dismiss()
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
                    Toast.makeText(requireContext(), "Cache cleared successfully", Toast.LENGTH_SHORT).show()
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
                _binding?.textCacheSize?.text = "%.1f MB used".format(mb)
            }
        }
    }

    private fun setupLogoutSection() {
        binding.btnSettingsLogout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of OpenChat?")
                .setPositiveButton("Log Out") { _, _ ->
                    settingsViewModel.logout()
                }
                .setNegativeButton("Cancel", null)
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
        val displayName = state.displayName.ifBlank { state.username.ifBlank { "User" } }
        binding.textSettingsName.text = displayName
        binding.textSettingsAvatar.text = displayName.take(1).uppercase()
        binding.textSettingsUsername.text = if (state.username.isNotBlank()) "@${state.username}" else ""

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
        binding.layoutBiometricToggle.visibility = if (state.isAppLockEnabled) View.VISIBLE else View.GONE
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
