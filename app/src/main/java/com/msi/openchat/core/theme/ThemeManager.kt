package com.msi.openchat.core.theme

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val storageKey: String, val displayName: String, val description: String) {
    SYSTEM("system", "System Default", "Follows device dark/light theme and wallpaper dynamic color"),
    LIGHT("light", "Light Theme", "Optimized crisp palette with high-contrast text and clean bubbles"),
    DARK("dark", "Dark Theme", "Deep dark palette optimized for low-light comfort")
}

/**
 * Robust ThemeManager for persisting and applying System / Light / Dark modes app-wide.
 * Persists selected mode to SharedPreferences synchronously and applies it on every launch
 * via AppCompatDelegate.setDefaultNightMode.
 */
class ThemeManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(getSavedThemeMode(context))
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    init {
        applyNightMode(_themeMode.value)
    }

    /**
     * Updates theme mode, persists synchronously to disk, applies night mode,
     * and recreates the calling activity with a smooth fade-in animation.
     */
    fun setThemeMode(mode: AppThemeMode, activity: Activity? = null) {
        val currentSaved = prefs.getString(KEY_THEME_MODE, null)
        if (_themeMode.value == mode && currentSaved == mode.storageKey) return

        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.storageKey).commit()
        applyNightMode(mode)

        activity?.let { act ->
            if (Build.VERSION.SDK_INT >= 34) {
                act.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.fade_in, R.anim.fade_out)
                act.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out)
            } else {
                @Suppress("DEPRECATION")
                act.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
            act.recreate()
        }
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
        const val PREFS_NAME = "openchat_theme_prefs"
        const val KEY_THEME_MODE = "app_theme_mode"

        fun getSavedThemeMode(context: Context): AppThemeMode {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedKey = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.storageKey)
            if (savedKey == "dynamic") return AppThemeMode.SYSTEM
            return AppThemeMode.values().firstOrNull { it.storageKey == savedKey } ?: AppThemeMode.SYSTEM
        }

        /**
         * Applies the persisted theme mode immediately.
         * Must be called in Application.onCreate() and Activity.onCreate() on cold start.
         */
        fun applySavedTheme(context: Context) {
            val mode = getSavedThemeMode(context)
            val targetNightMode = when (mode) {
                AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(targetNightMode)
        }
    }
}
