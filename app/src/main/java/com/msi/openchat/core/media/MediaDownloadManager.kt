package com.msi.openchat.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MediaDownloadManager
 *
 * Implements receiver-side media caching, automatic background download, and external export.
 * Rules:
 * 1. Download directly into app cache directory (cacheDir/openchat_cache/<subfolder>).
 * 2. Preview/play strictly from cache — never re-hits backend on repeated opens.
 * 3. Automatic background download triggered upon receiving realtime message.
 * 4. Export / Download button saves to MediaStore under OpenChat/ with subfolders:
 *    OpenChat/Images, OpenChat/Videos, OpenChat/Audio, OpenChat/Files.
 */
object MediaDownloadManager {
    private const val TAG = "MediaDownloadManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getCacheSubfolder(type: String): String {
        return when (type.lowercase()) {
            "image", "photo" -> "images"
            "video" -> "videos"
            "audio", "voice" -> "audio"
            else -> "files"
        }
    }

    fun getCacheDirForType(context: Context, type: String): File {
        val root = File(context.cacheDir, "openchat_cache")
        val sub = File(root, getCacheSubfolder(type))
        if (!sub.exists()) {
            sub.mkdirs()
        }
        return sub
    }

    /**
     * Constructs a safe, deterministic cache filename for a given message and media URL.
     */
    fun getCacheFileName(messageId: String, remoteUrl: String?, originalName: String?, type: String): String {
        val cleanName = originalName?.replace(Regex("[^a-zA-Z0-9._-]"), "_")?.takeIf { it.isNotBlank() }
        val ext = when {
            cleanName?.contains(".") == true -> cleanName.substringAfterLast(".")
            type.lowercase() in listOf("image", "photo") -> "jpg"
            type.lowercase() == "video" -> "mp4"
            type.lowercase() in listOf("audio", "voice") -> "m4a"
            else -> "bin"
        }
        val safeBase = if (cleanName != null) cleanName.substringBeforeLast(".") else "media"
        return "${messageId}_${safeBase}.$ext"
    }

    /**
     * Checks if the message's media file already exists in the local cache.
     * Never hits the network if found.
     */
    fun getCachedFile(
        context: Context,
        messageId: String,
        remoteUrl: String?,
        type: String,
        originalName: String? = null
    ): File? {
        val targetDir = getCacheDirForType(context, type)
        val expectedName = getCacheFileName(messageId, remoteUrl, originalName, type)
        val candidate = File(targetDir, expectedName)
        if (candidate.exists() && candidate.length() > 0) {
            return candidate
        }

        // Also check if any file beginning with messageId exists in targetDir
        val matching = targetDir.listFiles { file -> file.name.startsWith("${messageId}_") && file.length() > 0 }
        return matching?.firstOrNull()
    }

    /**
     * Resolves playable/viewable local file:
     * 1. Checks if localFilePath is an accessible content:// URI or existing file.
     * 2. Checks cache directory.
     * 3. Returns null if not locally available.
     */
    fun resolveLocalFile(context: Context, message: Message): File? {
        val path = message.localFilePath
        if (!path.isNullOrBlank()) {
            if (path.startsWith("file://")) {
                val f = File(path.removePrefix("file://"))
                if (f.exists() && f.length() > 0) return f
            } else if (path.startsWith("/")) {
                val f = File(path)
                if (f.exists() && f.length() > 0) return f
            }
        }
        return getCachedFile(context, message.id, message.mediaUrl, message.type, message.content)
    }

    /**
     * Triggers asynchronous background download for an incoming media message.
     */
    fun enqueueBackgroundDownload(context: Context, message: Message) {
        val remoteUrl = message.mediaUrl ?: return
        if (remoteUrl.isBlank()) return

        val messageId = message.id
        if (activeDownloads.containsKey(messageId)) return

        val cached = getCachedFile(context, messageId, remoteUrl, message.type, message.content)
        if (cached != null) {
            Log.d(TAG, "Media for $messageId already in cache: ${cached.absolutePath}")
            return
        }

        scope.launch {
            try {
                activeDownloads[messageId] = true
                Log.i(TAG, "Starting background download for incoming media: msg $messageId type ${message.type}")
                downloadToCache(
                    context = context,
                    messageId = messageId,
                    remoteUrl = remoteUrl,
                    type = message.type,
                    originalName = message.content
                )
            } catch (e: Exception) {
                Log.w(TAG, "Background download failed for $messageId: ${e.message}")
            } finally {
                activeDownloads.remove(messageId)
            }
        }
    }

    /**
     * Downloads remote media into the app cache with retry and progress callback.
     * Supports data: URIs, http://, https://, and NHost/Firebase public endpoints.
     */
    suspend fun downloadToCache(
        context: Context,
        messageId: String,
        remoteUrl: String,
        type: String,
        originalName: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        // Fast path: cache hit
        val existing = getCachedFile(context, messageId, remoteUrl, type, originalName)
        if (existing != null) {
            onProgress?.invoke(1.0f)
            return@withContext Result.success(existing)
        }

        val targetDir = getCacheDirForType(context, type)
        val fileName = getCacheFileName(messageId, remoteUrl, originalName, type)
        val targetFile = File(targetDir, fileName)
        val tempFile = File(targetDir, "${fileName}.download.tmp")

        try {
            // Case 1: Base64 data: URI
            if (remoteUrl.startsWith("data:")) {
                val raw = if (remoteUrl.contains(",")) remoteUrl.substringAfter(",") else remoteUrl
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                FileOutputStream(tempFile).use { it.write(bytes) }
                if (tempFile.renameTo(targetFile)) {
                    updateDatabaseLocalPath(messageId, targetFile.absolutePath)
                    onProgress?.invoke(1.0f)
                    return@withContext Result.success(targetFile)
                }
            }

            // Case 2: HTTP / HTTPS stream
            var attempts = 0
            var success = false
            while (!success && attempts < 3) {
                attempts++
                try {
                    val request = Request.Builder().url(remoteUrl).build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body ?: throw IllegalStateException("Empty response body")
                        val contentLength = body.contentLength()

                        body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var bytesRead: Int
                                var totalRead = 0L
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    if (contentLength > 0 && onProgress != null) {
                                        onProgress(totalRead.toFloat() / contentLength.toFloat())
                                    }
                                }
                                output.flush()
                            }
                        }

                        if (tempFile.exists() && tempFile.length() > 0) {
                            if (targetFile.exists()) targetFile.delete()
                            if (tempFile.renameTo(targetFile)) {
                                success = true
                                updateDatabaseLocalPath(messageId, targetFile.absolutePath)
                                onProgress?.invoke(1.0f)
                                Log.i(TAG, "Downloaded media to cache: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                                return@withContext Result.success(targetFile)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Download attempt $attempts failed for $messageId: ${e.message}")
                    delay(1000L * attempts)
                }
            }

            Result.failure(Exception("Failed to download media after 3 attempts"))
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $messageId: ${e.message}", e)
            Result.failure(e)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun updateDatabaseLocalPath(messageId: String, localPath: String) {
        scope.launch {
            try {
                ServiceLocator.messageDao?.updateLocalFilePath(messageId, "file://$localPath")
            } catch (e: Exception) {
                Log.w(TAG, "Could not update localFilePath in DB for $messageId: ${e.message}")
            }
        }
    }

    // =========================================================================
    // External Storage Export (MediaStore on API 29+)
    // =========================================================================

    /**
     * Saves a cached media file to external storage under:
     * OpenChat/Images, OpenChat/Videos, OpenChat/Audio, OpenChat/Files.
     */
    suspend fun saveToExternalStorage(
        context: Context,
        sourceFile: File,
        displayName: String?,
        type: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("Source file does not exist or is empty"))
            }

            val sanitizedName = (displayName?.takeIf { it.isNotBlank() } ?: sourceFile.name)
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val ext = sanitizedName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (type.lowercase()) {
                "image", "photo" -> "image/jpeg"
                "video" -> "video/mp4"
                "audio", "voice" -> "audio/mp4"
                else -> "application/octet-stream"
            }

            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val (contentUri, relativePath) = when (type.lowercase()) {
                    "image", "photo" -> Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/OpenChat/Images")
                    "video" -> Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/OpenChat/Videos")
                    "audio", "voice" -> Pair(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/OpenChat/Audio")
                    else -> Pair(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/OpenChat/Files")
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val targetUri = resolver.insert(contentUri, contentValues)
                    ?: return@withContext Result.failure(IllegalStateException("Could not create MediaStore record"))

                resolver.openOutputStream(targetUri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(targetUri, contentValues, null, null)

                Log.i(TAG, "Saved media to MediaStore: $targetUri ($relativePath/$sanitizedName)")
                Result.success(targetUri)
            } else {
                // Legacy storage for API < 29
                val baseDir = when (type.lowercase()) {
                    "image", "photo" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    "video" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    "audio", "voice" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }
                val subfolderName = when (type.lowercase()) {
                    "image", "photo" -> "OpenChat/Images"
                    "video" -> "OpenChat/Videos"
                    "audio", "voice" -> "OpenChat/Audio"
                    else -> "OpenChat/Files"
                }
                val folder = File(baseDir, subfolderName).apply { if (!exists()) mkdirs() }
                val targetFile = File(folder, sanitizedName)

                sourceFile.inputStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(Uri.fromFile(targetFile))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to external storage: ${e.message}", e)
            Result.failure(e)
        }
    }
}
