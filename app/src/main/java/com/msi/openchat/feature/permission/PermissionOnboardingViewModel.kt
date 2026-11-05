package com.msi.openchat.feature.permission

import android.content.Context
import androidx.lifecycle.ViewModel
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.permission.PermissionItemState
import com.msi.openchat.core.permission.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionOnboardingUiState(
    val items: List<PermissionItemState> = emptyList(),
    val isDoneEnabled: Boolean = false,
    val hasInteracted: Boolean = false,
    val isCompleted: Boolean = false
)

class PermissionOnboardingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionOnboardingUiState())
    val uiState: StateFlow<PermissionOnboardingUiState> = _uiState.asStateFlow()

    fun loadPermissions(context: Context) {
        val items = PermissionManager.getOnboardingPermissions(context)
        val allGranted = items.isNotEmpty() && items.all { it.isGranted }
        val anyHandled = items.any { it.isHandled }
        _uiState.value = _uiState.value.copy(
            items = items,
            isDoneEnabled = allGranted || anyHandled || _uiState.value.hasInteracted
        )
    }

    /**
     * Refreshes permission grant states from Android OS while preserving
     * the user's interaction/handled state for items already prompted.
     */
    fun refreshPermissions(context: Context) {
        val freshPermissions = PermissionManager.getOnboardingPermissions(context)
        val currentItemsMap = _uiState.value.items.associateBy { it.permission }

        val updatedItems = freshPermissions.map { fresh ->
            val existing = currentItemsMap[fresh.permission]
            if (existing != null) {
                fresh.copy(
                    isHandled = existing.isHandled || fresh.isGranted
                )
            } else {
                fresh
            }
        }

        val allGranted = updatedItems.isNotEmpty() && updatedItems.all { it.isGranted }
        val anyHandled = updatedItems.any { it.isHandled }

        _uiState.value = _uiState.value.copy(
            items = updatedItems,
            isDoneEnabled = allGranted || anyHandled || _uiState.value.hasInteracted
        )
    }

    fun onPermissionResult(permission: String, isGranted: Boolean) {
        val updatedItems = _uiState.value.items.map { item ->
            if (item.permission == permission) {
                item.copy(isGranted = isGranted, isHandled = true)
            } else {
                item
            }
        }

        val allGranted = updatedItems.isNotEmpty() && updatedItems.all { it.isGranted }
        val anyHandled = updatedItems.any { it.isHandled }

        _uiState.value = _uiState.value.copy(
            items = updatedItems,
            hasInteracted = true,
            isDoneEnabled = allGranted || anyHandled || true
        )
    }

    fun onDoneClicked(context: Context? = null) {
        context?.let { PermissionManager.markOnboardingCompletedOrSkipped(it) }
            ?: ServiceLocator.sessionManager.setPermissionOnboardingCompleted(true)
        _uiState.value = _uiState.value.copy(isCompleted = true)
    }

    fun onSkipClicked(context: Context? = null) {
        context?.let { PermissionManager.markOnboardingCompletedOrSkipped(it) }
            ?: ServiceLocator.sessionManager.setPermissionOnboardingCompleted(true)
        _uiState.value = _uiState.value.copy(isCompleted = true)
    }
}
