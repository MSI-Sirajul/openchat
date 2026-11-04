package com.msi.openchat.core.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.msi.openchat.data.local.dao.MessageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class UriMetadata(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    val durationSeconds: Int = 0
)

/**
 * PersistableUriManager
 * Handles media picking via ACTION_OPEN_DOCUMENT without copying files to internal storage.
 * Maintains persistable URI permissions with limit guard & revoke-oldest LRU logic.
 * Reads metadata, plays, previews, and dispatches viewing/sharing directly from content:// URIs.
 */
object PersistableUriManager {
    private const val TAG = "PersistableUriManager"

    // Safe ceiling for persisted URI permissions (Android system limits vary from 128 to 512)
    private const val MAX_PERSISTED_PERMISSIONS = 100

    /**
     * Persists read permissions for the content:// URI with a limit guard and revoke-oldest policy.
     */
    fun takePersistablePermission(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        val resolver = context.contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        try {
            val currentPerms = resolver.persistedUriPermissions
            // Check limit guard: if approaching capacity, release oldest permissions
            if (currentPerms.size >= MAX_PERSISTED_PERMISSIONS) {
                val excess = (currentPerms.size - MAX_PERSISTED_PERMISSIONS) + 1
                val oldestList = currentPerms.take(excess)
                for (oldPerm in oldestList) {
                    try {
                        resolver.releasePersistableUriPermission(oldPerm.uri, takeFlags)
                        Log.d(TAG, "Revoked oldest persistable permission: ${oldPerm.uri}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed releasing permission for ${oldPerm.uri}: ${e.message}")
                    }
                }
            }

            resolver.takePersistableUriPermission(uri, takeFlags)
            Log.d(TAG, "Persisted read permission for URI: $uri (active total: ${resolver.persistedUriPermissions.size})")
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException taking permission for $uri: ${e.message}. Attempting revoke-oldest retry.")
            try {
                val perms = resolver.persistedUriPermissions
                if (perms.isNotEmpty()) {
                    resolver.releasePersistableUriPermission(perms.first().uri, takeFlags)
                    resolver.takePersistableUriPermission(uri, takeFlags)
                    return true
                }
            } catch (retryEx: Exception) {
                Log.e(TAG, "Retry failed taking permission for $uri: ${retryEx.message}")
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error taking permission for $uri", e)
            return false
        }
    }

    /**
     * Extracts display name, size, MIME type, and media duration directly from a content:// URI.
     */
    fun extractMetadata(context: Context, uri: Uri): UriMetadata {
        var name = "attachment_${System.currentTimeMillis()}"
        var size: Long = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)?.let { name = it }
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query OpenableColumns for $uri: ${e.message}")
        }

        if (size <= 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Exception) {}
        }

        var mimeType = context.contentResolver.getType(uri).orEmpty()
        if (mimeType.isBlank()) {
            val ext = name.substringAfterLast('.', "").lowercase()
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        var durationSeconds = 0
        if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationSeconds = (durationStr?.toLongOrNull() ?: 0L).toInt() / 1000
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract duration from $uri: ${e.message}")
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        return UriMetadata(
            uri = uri,
            displayName = name,
            size = size,
            mimeType = mimeType,
            durationSeconds = durationSeconds
        )
    }

    /**
     * Checks if a content:// URI is currently accessible (not deleted, permission not revoked).
     */
    fun isUriAccessible(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun isUriAccessible(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val f = File(uri.path.orEmpty())
            return f.exists() && f.length() > 0
        }
        return isUriAccessible(context, uri)
    }

    /**
     * Dispatches ACTION_VIEW directly on the content:// URI with FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun openDocument(context: Context, uri: Uri, mimeType: String? = null, displayName: String = "") {
        if (!isUriAccessible(context, uri)) {
            Toast.makeText(context, "File is no longer accessible or has been deleted from your device", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val effectiveMime = mimeType?.takeIf { it.isNotBlank() }
                ?: context.contentResolver.getType(uri)
                ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, effectiveMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val title = displayName.ifBlank { "file" }
            val chooser = Intent.createChooser(intent, "Open $title").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            val title = displayName.ifBlank { "file" }
            Toast.makeText(context, "No app available to open this file format ($title)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URI: ${e.message}", e)
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dispatches ACTION_SEND directly with FLAG_GRANT_READ_URI_PERMISSION without duplicating file data.
     */
    fun shareDocument(context: Context, uri: Uri, mimeType: String? = null, displayName: String = "") {
        if (!isUriAccessible(context, uri)) {
            Toast.makeText(context, "File is no longer accessible or has been deleted from your device", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val effectiveMime = mimeType?.takeIf { it.isNotBlank() }
                ?: context.contentResolver.getType(uri)
                ?: "*/*"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = effectiveMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val title = displayName.ifBlank { "file" }
            val chooser = Intent.createChooser(intent, "Share $title").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing URI: ${e.message}", e)
            Toast.makeText(context, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Migrates legacy local file path records in Room to URI representations,
     * then deletes internal storage media copies to save space.
     */
    suspend fun migrateLegacyMediaToUris(context: Context, messageDao: MessageDao?) = withContext(Dispatchers.IO) {
        if (messageDao == null) return@withContext
        try {
            val messagesWithPaths = messageDao.getAllMessagesWithLocalPath()
            var migratedCount = 0

            for (entity in messagesWithPaths) {
                val path = entity.localFilePath ?: continue
                if (!path.startsWith("content://")) {
                    val cleanPath = path.removePrefix("file://")
                    val file = File(cleanPath)
                    if (file.exists()) {
                        val uriString = try {
                            val authority = "${context.packageName}.fileprovider"
                            FileProvider.getUriForFile(context, authority, file).toString()
                        } catch (_: Exception) {
                            Uri.fromFile(file).toString()
                        }
                        messageDao.updateLocalFilePath(entity.id, uriString)
                        // Delete the old internal copy
                        file.delete()
                        migratedCount++
                    }
                }
            }

            // Cleanup any remaining lingering internal media files
            val legacyDir = File(context.filesDir, "openchat_offline_media")
            if (legacyDir.exists() && legacyDir.isDirectory) {
                legacyDir.listFiles()?.forEach { it.delete() }
                legacyDir.delete()
            }

            Log.i(TAG, "Completed migration: converted $migratedCount legacy records to URIs and purged internal copies.")
        } catch (e: Exception) {
            Log.w(TAG, "Media migration encountered error: ${e.message}")
        }
    }
}
