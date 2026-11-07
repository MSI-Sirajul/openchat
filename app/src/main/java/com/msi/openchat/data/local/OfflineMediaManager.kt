package com.msi.openchat.data.local

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.local.dao.MessageDao
import com.msi.openchat.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OfflineMediaManager ensures that images, videos, audio notes, and attachments
 * are stored in persistent local device storage so they remain 100% accessible
 * when offline or without an active internet connection.
 */
object OfflineMediaManager {

    private const val TAG = "OfflineMediaManager"
    private const val MEDIA_DIR_NAME = "openchat_offline_media"

    fun getUserMediaDirectory(context: Context, userId: String? = null): File {
        val uid = userId ?: try {
            ServiceLocator.sessionManager.getUserId()
        } catch (_: Exception) {
            null
        } ?: "shared"
        val cleanUid = sanitizeFileName(uid)
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val userDir = File(baseDir, "user_media/$cleanUid")
        if (!userDir.exists()) {
            userDir.mkdirs()
        }
        return userDir
    }

    fun getMediaDirectory(context: Context): File {
        return getUserMediaDirectory(context)
    }

    fun deleteUserMedia(context: Context, userId: String) {
        val cleanUid = sanitizeFileName(userId)
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val externalUserDir = File(baseDir, "user_media/$cleanUid")
        if (externalUserDir.exists()) {
            externalUserDir.deleteRecursively()
        }
        val internalUserDir = File(context.filesDir, "user_media/$cleanUid")
        if (internalUserDir.exists()) {
            internalUserDir.deleteRecursively()
        }
        Log.i(TAG, "Deleted offline media cache for user $userId")
    }

    /**
     * Copies a local file (e.g. from picker or cache) into permanent offline storage.
     */
    fun saveMediaLocally(context: Context, sourceFile: File, preferredFileName: String): File {
        val mediaDir = getMediaDirectory(context)
        val cleanName = sanitizeFileName(preferredFileName)
        val targetFile = File(mediaDir, cleanName)

        if (sourceFile.absolutePath != targetFile.absolutePath) {
            sourceFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        Log.d(TAG, "Saved media locally at ${targetFile.absolutePath} (${targetFile.length()} bytes)")
        return targetFile
    }

    /**
     * Streams input stream into permanent offline storage.
     */
    fun saveInputStreamLocally(context: Context, inputStream: InputStream, preferredFileName: String): File {
        val mediaDir = getMediaDirectory(context)
        val cleanName = sanitizeFileName(preferredFileName)
        val targetFile = File(mediaDir, cleanName)

        FileOutputStream(targetFile).use { output ->
            inputStream.copyTo(output)
        }
        Log.d(TAG, "Streamed media locally to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
        return targetFile
    }

    /**
     * Decodes Base64 data string (with or without data: URI prefix) directly into offline cache.
     */
    fun saveBase64Locally(context: Context, base64DataString: String, preferredFileName: String): File? {
        return try {
            val rawBase64 = if (base64DataString.contains(",")) {
                base64DataString.substringAfter(",")
            } else {
                base64DataString
            }
            val decodedBytes = Base64.decode(rawBase64, Base64.DEFAULT)
            val mediaDir = getMediaDirectory(context)
            val cleanName = sanitizeFileName(preferredFileName)
            val targetFile = File(mediaDir, cleanName)

            FileOutputStream(targetFile).use { output ->
                output.write(decodedBytes)
            }
            Log.d(TAG, "Saved base64 data locally to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 data to local file: ${e.message}", e)
            null
        }
    }

    /**
     * Checks if a local file exists on disk and has valid content.
     */
    fun getLocalFileIfAvailable(localPath: String?): File? {
        if (localPath.isNullOrBlank()) return null
        val cleanPath = localPath.removePrefix("file://")
        val file = File(cleanPath)
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    private fun getExtensionForType(type: String, fallback: String = "bin"): String {
        return when (type.lowercase()) {
            "image" -> "jpg"
            "video" -> "mp4"
            "audio", "voice" -> "m4a"
            "file" -> fallback
            else -> fallback
        }
    }

    /**
     * Resolves message media file from local disk cache, or caches it immediately if present as Base64 data URI.
     * If it's a remote HTTP URL, starts background download and notifies via onCached callback.
     */
    fun resolveMessageMediaFile(
        context: Context,
        message: Message,
        onCached: ((File) -> Unit)? = null
    ): File? {
        // 1. Direct local file path check
        val existingLocal = getLocalFileIfAvailable(message.localFilePath)
        if (existingLocal != null) return existingLocal

        val mediaUrl = message.mediaUrl
        if (mediaUrl.isNullOrBlank()) return null

        // 2. Direct file:// or local path in mediaUrl
        if (mediaUrl.startsWith("file://") || mediaUrl.startsWith("/")) {
            val local = getLocalFileIfAvailable(mediaUrl)
            if (local != null) return local
        }

        // 3. Check if cached by standard filename pattern
        val mediaDir = getMediaDirectory(context)
        val defaultName = "${message.id}_${sanitizeFileName(message.content ?: "media")}.${getExtensionForType(message.type)}"
        val standardCachedFile = File(mediaDir, defaultName)
        if (standardCachedFile.exists() && standardCachedFile.length() > 0) {
            return standardCachedFile
        }

        // Also check if any file in mediaDir starts with message.id
        val existingMatching = mediaDir.listFiles()?.firstOrNull { it.name.startsWith(message.id) && it.length() > 0 }
        if (existingMatching != null) {
            return existingMatching
        }

        // 4. Data URI handling (instant local decoding & persistent caching)
        if (mediaUrl.startsWith("data:")) {
            val ext = if (mediaUrl.startsWith("data:image/")) "jpg"
            else if (mediaUrl.startsWith("data:video/")) "mp4"
            else if (mediaUrl.startsWith("data:audio/")) "m4a"
            else getExtensionForType(message.type)

            val fileName = "${message.id}_media.$ext"
            val decodedFile = saveBase64Locally(context, mediaUrl, fileName)
            if (decodedFile != null && decodedFile.exists() && decodedFile.length() > 0) {
                // Update local Room database so subsequent app launches load from local storage
                try {
                    CoroutineScope(Dispatchers.IO).launch {
                        ServiceLocator.messageDao?.updateLocalFilePath(message.id, decodedFile.absolutePath)
                    }
                } catch (_: Exception) {}
                onCached?.invoke(decodedFile)
                return decodedFile
            }
        }

        // 5. Remote HTTP/HTTPS URL
        if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
            cacheRemoteMediaAsync(
                context = context,
                messageId = message.id,
                remoteUrl = mediaUrl,
                preferredFileName = defaultName,
                messageDao = ServiceLocator.messageDao,
                onComplete = onCached
            )
        }

        return null
    }

    /**
     * Proactively caches media for any incoming or loaded message.
     */
    fun cacheMessageMediaProactively(context: Context, message: Message, messageDao: MessageDao?) {
        // No-op: Media and attachments are NOT copied to internal storage.
    }

    /**
     * Downloads remote media in background, caches it locally, and updates Room database entity.
     */
    fun cacheRemoteMediaAsync(
        context: Context,
        messageId: String,
        remoteUrl: String,
        preferredFileName: String,
        messageDao: MessageDao?,
        onComplete: ((File) -> Unit)? = null
    ) {
        if (remoteUrl.isBlank() || remoteUrl.startsWith("file://") || remoteUrl.startsWith("content://")) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mediaDir = getMediaDirectory(context)
                val cleanName = sanitizeFileName(preferredFileName)
                val targetFile = File(mediaDir, cleanName)

                if (targetFile.exists() && targetFile.length() > 0) {
                    messageDao?.updateLocalFilePath(messageId, targetFile.absolutePath)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(targetFile)
                    }
                    return@launch
                }

                if (remoteUrl.startsWith("data:")) {
                    val decoded = saveBase64Locally(context, remoteUrl, cleanName)
                    if (decoded != null && decoded.exists()) {
                        messageDao?.updateLocalFilePath(messageId, decoded.absolutePath)
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(decoded)
                        }
                    }
                    return@launch
                }

                val url = URL(remoteUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    requestMethod = "GET"
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Successfully cached remote media offline for message $messageId: ${targetFile.absolutePath}")
                    messageDao?.updateLocalFilePath(messageId, targetFile.absolutePath)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(targetFile)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache remote media offline: ${e.message}")
            }
        }
    }

    /**
     * Resolves the MIME type from a file extension or filename.
     */
    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mappedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!mappedMime.isNullOrBlank()) {
            return mappedMime
        }
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m4a", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            else -> "application/octet-stream"
        }
    }

    /**
     * Opens an attachment file using Android's Intent.ACTION_VIEW and FileProvider according to file extension.
     */
    fun openAttachment(context: Context, message: Message) {
        val fileName = message.content?.takeIf { it.isNotBlank() && it != "Attachment" && it != "📎 Document" }
            ?: "attachment_${message.id.take(8)}"

        val localPath = message.localFilePath
        if (!localPath.isNullOrBlank() && localPath.startsWith("content://")) {
            val uri = Uri.parse(localPath)
            if (com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(context, uri)) {
                com.msi.openchat.core.media.PersistableUriManager.openDocument(
                    context = context,
                    uri = uri,
                    mimeType = getMimeType(fileName),
                    displayName = fileName
                )
            } else {
                Toast.makeText(context, "Attachment is no longer accessible or permission was revoked", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val resolvedFile = resolveMessageMediaFile(context, message)
        if (resolvedFile != null && resolvedFile.exists() && resolvedFile.length() > 0) {
            openFileWithIntent(context, resolvedFile, fileName)
            return
        }

        // If not immediately available, show progress toast and resolve in background
        Toast.makeText(context, "Preparing attachment...", Toast.LENGTH_SHORT).show()
        val mediaUrl = message.mediaUrl
        if (mediaUrl.isNullOrBlank()) {
            Toast.makeText(context, "Attachment file data unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ext = fileName.substringAfterLast('.', "")
            val targetFileName = "${message.id}_${sanitizeFileName(fileName)}${if (ext.isBlank()) ".bin" else ""}"
            val mediaDir = getMediaDirectory(context)
            val file = File(mediaDir, targetFileName)

            var success = false
            try {
                if (mediaUrl.startsWith("data:")) {
                    val decoded = saveBase64Locally(context, mediaUrl, targetFileName)
                    success = decoded != null && decoded.exists()
                } else if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
                    val url = URL(mediaUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                    }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        conn.inputStream.use { input ->
                            FileOutputStream(file).use { out -> input.copyTo(out) }
                        }
                        success = file.exists() && file.length() > 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading attachment: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                if (success && file.exists()) {
                    ServiceLocator.messageDao?.let { dao ->
                        CoroutineScope(Dispatchers.IO).launch {
                            dao.updateLocalFilePath(message.id, file.absolutePath)
                        }
                    }
                    openFileWithIntent(context, file, fileName)
                } else {
                    Toast.makeText(context, "Unable to load attachment file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Dispatches Intent.ACTION_VIEW with FileProvider and grant permissions.
     */
    fun openFileWithIntent(context: Context, file: File, displayFileName: String) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)
            val mimeType = getMimeType(displayFileName.ifBlank { file.name })

            Log.d(TAG, "Opening file $displayFileName (${file.absolutePath}) with URI $contentUri, MIME: $mimeType")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Open $displayFileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity found to handle file: ${e.message}")
            Toast.makeText(context, "No app found to open this file format ($displayFileName)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open attachment: ${e.message}", e)
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
