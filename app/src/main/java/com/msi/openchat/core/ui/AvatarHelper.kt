package com.msi.openchat.core.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.msi.openchat.data.model.Profile

/**
 * AvatarHelper
 *
 * Centralized utility for loading user avatars with Coil across the application:
 * - Automatically checks if avatarUrl is present and non-blank.
 * - Loads remote image with crossfade and circular cropping.
 * - Manages visibility: displays ImageView on successful load, falls back to letter TextView with initials on error or null URL.
 * - Strictly isolates user avatars to avoid reusing cached images across different users.
 */
object AvatarHelper {

    /**
     * Resolves the avatar URL from a Profile, or returns null if not set.
     */
    fun resolveAvatarUrl(profile: Profile?): String? {
        val url = profile?.avatarUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: profile?.avatar?.trim()?.takeIf { it.isNotBlank() }
        return url
    }

    /**
     * Resolves the avatar URL string or returns null.
     */
    fun resolveAvatarUrl(url: String?): String? {
        return url?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns the first letter initial from a display name, username, or default "U".
     */
    fun extractInitial(displayName: String?): String {
        return displayName?.trim()?.take(1)?.uppercase()?.ifBlank { "U" } ?: "U"
    }

    /**
     * Loads an avatar into an ImageView using Coil with fallback to an initials TextView.
     * When avatarUrl is present and valid, displays the ImageView and hides the letter TextView.
     * When avatarUrl is null/blank or fails to load, displays the letter TextView with initials and hides the ImageView.
     */
    fun loadAvatar(
        imageView: ImageView,
        letterTextView: TextView,
        avatarUrl: String?,
        displayName: String?
    ) {
        val resolvedUrl = resolveAvatarUrl(avatarUrl)
        val initial = extractInitial(displayName)

        if (!resolvedUrl.isNullOrBlank()) {
            imageView.visibility = View.VISIBLE
            letterTextView.visibility = View.GONE
            imageView.load(resolvedUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
                listener(
                    onSuccess = { _, _ ->
                        imageView.visibility = View.VISIBLE
                        letterTextView.visibility = View.GONE
                    },
                    onError = { _, _ ->
                        imageView.visibility = View.GONE
                        letterTextView.visibility = View.VISIBLE
                        letterTextView.text = initial
                    }
                )
            }
        } else {
            imageView.visibility = View.GONE
            letterTextView.visibility = View.VISIBLE
            letterTextView.text = initial
        }
    }

    /**
     * Overload accepting Profile directly.
     */
    fun loadAvatar(
        imageView: ImageView,
        letterTextView: TextView,
        profile: Profile?
    ) {
        val name = profile?.getResolvedName() ?: profile?.displayName ?: profile?.username ?: "User"
        loadAvatar(imageView, letterTextView, resolveAvatarUrl(profile), name)
    }
}
