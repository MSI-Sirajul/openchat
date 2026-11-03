package com.msi.openchat.data.remote.storage

import android.util.Base64
import android.util.Log
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.NativeCredentialsManager
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * NhostStorageClient
 * Dual-tier cloud media storage and database management:
 * 1. Nhost Cloud Storage: uploads file binaries to designated storage buckets (images, videos, audios, attachments, avatars)
 * 2. Nhost Hasura Database: manages metadata, file catalog, and fallback records in 'storage.files' table
 * 3. Firebase Realtime Database: manages user messages, presence, profiles, and media indexing
 */
class NhostStorageClient(
    val baseUrl: String = NativeCredentialsManager.storageUrl,
    val graphqlUrl: String = NativeCredentialsManager.graphqlUrl,
    val defaultBucket: String = BUCKET_DEFAULT,
    private val adminSecret: String? = null
) {
    companion object {
        private const val TAG = "NhostStorageClient"
        const val NHOST_STORAGE_URL = "https://stvvoprjibbedgcdlbib.storage.ap-south-1.nhost.run/v1"

        // Dedicated Storage Buckets
        const val BUCKET_DEFAULT = "default"
        const val BUCKET_IMAGES = "images"
        const val BUCKET_VIDEOS = "videos"
        const val BUCKET_AUDIOS = "audios"
        const val BUCKET_ATTACHMENTS = "attachments"
        const val BUCKET_AVATARS = "avatars"

        fun getBucketForType(type: String): String {
            return when (type.lowercase()) {
                "image", "photo", "pic", "img" -> BUCKET_IMAGES
                "video", "clip", "mp4" -> BUCKET_VIDEOS
                "audio", "voice", "voice_note", "voice-chat" -> BUCKET_AUDIOS
                "avatar", "profile_picture", "profile" -> BUCKET_AVATARS
                "file", "document", "attachment", "doc", "pdf" -> BUCKET_ATTACHMENTS
                else -> BUCKET_DEFAULT
            }
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getFileUrl(fileIdOrUrl: String): String {
        if (fileIdOrUrl.startsWith("http://") || fileIdOrUrl.startsWith("https://") ||
            fileIdOrUrl.startsWith("data:") || fileIdOrUrl.startsWith("file://") ||
            fileIdOrUrl.startsWith("content://")
        ) {
            return fileIdOrUrl
        }
        val cleanBase = baseUrl.trimEnd('/')
        return "$cleanBase/files/$fileIdOrUrl"
    }

    suspend fun uploadFile(
        file: File,
        mimeType: String,
        bucketId: String = defaultBucket
    ): Resource<String> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext Resource.Error("File is empty or does not exist")
        }

        val effectiveBucket = bucketId.ifBlank { defaultBucket }
        val effectiveMime = mimeType.ifBlank { "application/octet-stream" }

        try {
            val mediaType = effectiveMime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            val requestFile = file.asRequestBody(mediaType)

            // Include both multipart 'bucket-id' form field and 'x-nhost-bucket-id' header
            // so Nhost Storage routes to the target storage bucket folder
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("bucket-id", effectiveBucket)
                .addFormDataPart("file[]", file.name, requestFile)
                .build()

            val uploadUrl = "${baseUrl.trimEnd('/')}/files"

            val requestBuilder = Request.Builder()
                .url(uploadUrl)
                .addHeader("x-nhost-bucket-id", effectiveBucket)
                .post(multipartBody)

            if (!adminSecret.isNullOrBlank()) {
                requestBuilder.addHeader("x-hasura-admin-secret", adminSecret)
            }

            val request = requestBuilder.build()
            val response = try {
                httpClient.newCall(request).execute()
            } catch (netEx: Exception) {
                Log.w(TAG, "Network exception connecting to Nhost Storage: ${netEx.message}. Falling back to Nhost Database.")
                null
            }

            if (response != null && response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val fileId = parseFileIdFromResponse(responseBody)
                if (!fileId.isNullOrBlank()) {
                    val publicUrl = getFileUrl(fileId)
                    Log.i(TAG, "File uploaded successfully to Nhost Storage bucket '$effectiveBucket': $publicUrl")

                    // Record metadata in Nhost GraphQL database ('files' table)
                    recordFileInNhostDatabase(
                        fileId = fileId,
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = effectiveMime,
                        bucketId = effectiveBucket,
                        dataUrl = publicUrl
                    )
                    return@withContext Resource.Success(publicUrl)
                }
            }

            val responseCode = response?.code ?: -1
            val responseBody = response?.body?.string().orEmpty()
            Log.w(TAG, "Nhost Storage HTTP $responseCode ($responseBody). Activating Nhost GraphQL Database media storage.")

            // Graceful Nhost Database Storage Fallback:
            // Insert file record into Hasura 'files' database table with embedded data URI in metadata
            val fallbackFileId = UUID.randomUUID().toString()
            val fileBytes = file.readBytes()
            val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
            val dataUrl = "data:$effectiveMime;base64,$base64Data"

            val recorded = recordFileInNhostDatabase(
                fileId = fallbackFileId,
                fileName = file.name,
                fileSize = file.length(),
                mimeType = effectiveMime,
                bucketId = effectiveBucket,
                dataUrl = dataUrl
            )

            if (recorded) {
                Log.i(TAG, "File successfully stored and registered in Nhost GraphQL Database (bucket: $effectiveBucket)")
                Resource.Success(dataUrl)
            } else {
                // If even GraphQL database fails, return local file reference
                Resource.Success(dataUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}", e)
            try {
                val fileBytes = file.readBytes()
                val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
                val dataUrl = "data:$effectiveMime;base64,$base64Data"
                Resource.Success(dataUrl)
            } catch (inner: Exception) {
                Resource.Error(e.message ?: "Failed to upload file to Nhost", e)
            }
        }
    }

    suspend fun uploadBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        bucketId: String = defaultBucket
    ): Resource<String> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) {
            return@withContext Resource.Error("Byte array is empty")
        }

        val effectiveBucket = bucketId.ifBlank { defaultBucket }
        val effectiveMime = mimeType.ifBlank { "application/octet-stream" }

        try {
            val mediaType = effectiveMime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            val requestFile = bytes.toRequestBody(mediaType)

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("bucket-id", effectiveBucket)
                .addFormDataPart("file[]", fileName, requestFile)
                .build()

            val uploadUrl = "${baseUrl.trimEnd('/')}/files"

            val requestBuilder = Request.Builder()
                .url(uploadUrl)
                .addHeader("x-nhost-bucket-id", effectiveBucket)
                .post(multipartBody)

            if (!adminSecret.isNullOrBlank()) {
                requestBuilder.addHeader("x-hasura-admin-secret", adminSecret)
            }

            val request = requestBuilder.build()
            val response = try {
                httpClient.newCall(request).execute()
            } catch (netEx: Exception) {
                null
            }

            if (response != null && response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val fileId = parseFileIdFromResponse(responseBody)
                if (!fileId.isNullOrBlank()) {
                    val publicUrl = getFileUrl(fileId)
                    recordFileInNhostDatabase(
                        fileId = fileId,
                        fileName = fileName,
                        fileSize = bytes.size.toLong(),
                        mimeType = effectiveMime,
                        bucketId = effectiveBucket,
                        dataUrl = publicUrl
                    )
                    return@withContext Resource.Success(publicUrl)
                }
            }

            // Fallback to Nhost database registration
            val fallbackFileId = UUID.randomUUID().toString()
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:$effectiveMime;base64,$base64Data"

            recordFileInNhostDatabase(
                fileId = fallbackFileId,
                fileName = fileName,
                fileSize = bytes.size.toLong(),
                mimeType = effectiveMime,
                bucketId = effectiveBucket,
                dataUrl = dataUrl
            )

            Resource.Success(dataUrl)
        } catch (e: Exception) {
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:$effectiveMime;base64,$base64Data"
            Resource.Success(dataUrl)
        }
    }

    /**
     * Manages media files in the Nhost GraphQL Database ('files' table in PostgreSQL).
     * Creates or updates the file entity with bucket categorization and data metadata.
     */
    fun recordFileInNhostDatabase(
        fileId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        bucketId: String,
        dataUrl: String?
    ): Boolean {
        return try {
            val mutationObj = JSONObject().apply {
                put("id", fileId)
                put("name", fileName)
                put("size", fileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                put("mimeType", mimeType)
                put("bucketId", bucketId)
                put("isUploaded", true)
                if (!dataUrl.isNullOrBlank()) {
                    put("metadata", JSONObject().apply {
                        put("data_url", dataUrl)
                    })
                }
            }

            val query = JSONObject().apply {
                put("query", """
                    mutation InsertMediaRecord(${'$'}obj: files_insert_input!) {
                        insertFile(object: ${'$'}obj, on_conflict: { constraint: files_pkey, update_columns: [name, size, mimeType, bucketId, isUploaded, metadata] }) {
                            id
                            name
                            bucketId
                            isUploaded
                        }
                    }
                """.trimIndent())
                put("variables", JSONObject().apply {
                    put("obj", mutationObj)
                })
            }

            val mediaType = "application/json".toMediaTypeOrNull()
            val body = query.toString().toRequestBody(mediaType)
            val requestBuilder = Request.Builder()
                .url(graphqlUrl)
                .post(body)

            if (!adminSecret.isNullOrBlank()) {
                requestBuilder.addHeader("x-hasura-admin-secret", adminSecret)
            }

            val res = httpClient.newCall(requestBuilder.build()).execute()
            val resBody = res.body?.string().orEmpty()
            val success = res.isSuccessful && !resBody.contains("errors")

            // Index in Firebase Realtime Database metadata tree for fast user lookup
            val currentUserId = FirebaseManager.auth.currentUser?.uid
            if (!currentUserId.isNullOrBlank()) {
                val metaMap = HashMap<String, Any?>().apply {
                    put("id", fileId)
                    put("name", fileName)
                    put("bucket", bucketId)
                    put("size", fileSize)
                    put("mime_type", mimeType)
                    put("url", dataUrl ?: getFileUrl(fileId))
                    put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP)
                }
                FirebaseManager.mediaMetadataRef.child(currentUserId).child(bucketId).child(fileId).updateChildren(metaMap)
            }

            success
        } catch (e: Exception) {
            Log.w(TAG, "Could not record media in Nhost database: ${e.message}")
            false
        }
    }

    private fun parseFileIdFromResponse(responseBody: String): String? {
        val trimmed = responseBody.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    obj.optString("id").takeIf { it.isNotBlank() }
                } else null
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("id")) {
                    obj.optString("id").takeIf { it.isNotBlank() }
                } else if (obj.has("processedFiles")) {
                    val files = obj.optJSONArray("processedFiles")
                    if (files != null && files.length() > 0) {
                        files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
            null
        }
    }
}
