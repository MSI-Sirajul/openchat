package com.msi.openchat.core.ui

import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.R
import com.msi.openchat.data.model.Profile

/**
 * AvatarHelper
 *
 * Centralized utility for loading user avatars with Coil across the application:
 * - Replaces first-letter avatars with a default person icon fallback (R.drawable.ic_default_avatar).
 * - Loads remote image with crossfade and circular cropping.
 * - Shows uploaded image everywhere (profile, toolbar, friend lists, chat headers).
 * - Uses the default person icon as placeholder, error, and fallback.
 * - Supports local URI preview during editing prior to saving.
 * - Strictly isolates user avatars by loading each user's own avatar_url.
 */
object AvatarHelper {

    /**
     * Resolves the avatar URL from a Profile, or returns null if not set.
     */
    fun resolveAvatarUrl(profile: Profile?): String? {
        return profile?.avatarUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: profile?.avatar?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Resolves the avatar URL string or returns null.
     */
    fun resolveAvatarUrl(url: String?): String? {
        return url?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns the first letter initial from a display name, username, or default "U".
     * Maintained for backwards-compatibility if needed.
     */
    fun extractInitial(displayName: String?): String {
        return displayName?.trim()?.take(1)?.uppercase()?.ifBlank { "U" } ?: "U"
    }

    /**
     * Loads an avatar into an ImageView using Coil with fallback to the default person icon.
     * When avatarUrl is present and valid, loads and displays the image.
     * When avatarUrl is null/blank or fails to load, displays R.drawable.ic_default_avatar.
     * The first-letter TextView is hidden (View.GONE) in favor of the default person icon.
     */
    fun loadAvatar(
        imageView: ImageView,
        letterTextView: TextView? = null,
        avatarUrl: String?,
        displayName: String? = null
    ) {
        letterTextView?.visibility = View.GONE
        imageView.visibility = View.VISIBLE

        val resolvedUrl = resolveAvatarUrl(avatarUrl)

        if (!resolvedUrl.isNullOrBlank()) {
            imageView.load(resolvedUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_default_avatar)
                error(R.drawable.ic_default_avatar)
                fallback(R.drawable.ic_default_avatar)
                transformations(CircleCropTransformation())
                listener(
                    onSuccess = { _, _ ->
                        imageView.visibility = View.VISIBLE
                        letterTextView?.visibility = View.GONE
                    },
                    onError = { _, _ ->
                        imageView.visibility = View.VISIBLE
                        letterTextView?.visibility = View.GONE
                        imageView.setImageResource(R.drawable.ic_default_avatar)
                    }
                )
            }
        } else {
            imageView.setImageResource(R.drawable.ic_default_avatar)
            imageView.visibility = View.VISIBLE
            letterTextView?.visibility = View.GONE
        }
    }

    /**
     * Previews a locally selected avatar Uri (before saving to server).
     */
    fun previewAvatar(
        imageView: ImageView,
        letterTextView: TextView? = null,
        imageUri: Uri
    ) {
        letterTextView?.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        imageView.load(imageUri) {
            crossfade(true)
            placeholder(R.drawable.ic_default_avatar)
            error(R.drawable.ic_default_avatar)
            fallback(R.drawable.ic_default_avatar)
            transformations(CircleCropTransformation())
        }
    }

    /**
     * Overload accepting Profile directly.
     */
    fun loadAvatar(
        imageView: ImageView,
        letterTextView: TextView? = null,
        profile: Profile?
    ) {
        val name = profile?.getResolvedName() ?: profile?.displayName ?: profile?.username ?: "User"
        loadAvatar(imageView, letterTextView, resolveAvatarUrl(profile), name)
    }
}
