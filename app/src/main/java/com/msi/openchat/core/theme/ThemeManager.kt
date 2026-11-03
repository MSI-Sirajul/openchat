package com.msi.openchat.core.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val storageKey: String, val displayName: String, val description: String) {
    SYSTEM("system", "System Default", "Follows device dark/light theme and wallpaper dynamic color"),
    LIGHT("light", "Light Theme", "Optimized crisp palette with high-contrast text and clean bubbles"),
    DARK("dark", "Dark Theme", "Deep dark palette optimized for low-light comfort")
}

class ThemeManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadInitialThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    init {
        applyNightMode(_themeMode.value)
    }

    private fun loadInitialThemeMode(): AppThemeMode {
        val savedKey = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.storageKey)
        // If user previously selected dynamic, combine directly with SYSTEM as requested
        if (savedKey == "dynamic") return AppThemeMode.SYSTEM
        return AppThemeMode.values().firstOrNull { it.storageKey == savedKey } ?: AppThemeMode.SYSTEM
    }

    fun setThemeMode(mode: AppThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
        applyNightMode(mode)
    }

    private fun applyNightMode(mode: AppThemeMode) {
        val targetNightMode = when (mode) {
            AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(targetNightMode)
    }

    fun isDarkMode(context: Context): Boolean {
        return when (_themeMode.value) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> {
                val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "openchat_theme_prefs"
        private const val KEY_THEME_MODE = "app_theme_mode"
    }
}
