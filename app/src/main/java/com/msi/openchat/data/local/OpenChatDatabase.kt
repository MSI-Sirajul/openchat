package com.msi.openchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.msi.openchat.data.local.dao.ConversationDao
import com.msi.openchat.data.local.dao.MessageDao
import com.msi.openchat.data.local.entity.ConversationEntity
import com.msi.openchat.data.local.entity.MessageEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Main Room Database for local caching of chat history and conversations.
 * Version 5 namespaces databases strictly per logged-in user to prevent cross-account cache leakage.
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class OpenChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        private const val BASE_DB_PREFIX = "openchat_user_"
        private const val DEFAULT_DB_NAME = "openchat_v5.db"

        private val userInstances = ConcurrentHashMap<String, OpenChatDatabase>()

        fun sanitizeUserId(userId: String): String {
            return userId.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        }

        fun getDatabaseNameForUser(userId: String?): String {
            if (userId.isNullOrBlank()) return DEFAULT_DB_NAME
            return "${BASE_DB_PREFIX}${sanitizeUserId(userId)}.db"
        }

        fun getInstance(context: Context, userId: String? = null): OpenChatDatabase {
            val key = if (userId.isNullOrBlank()) "default" else sanitizeUserId(userId)
            return userInstances.getOrPut(key) {
                synchronized(this) {
                    userInstances[key] ?: Room.databaseBuilder(
                        context.applicationContext,
                        OpenChatDatabase::class.java,
                        getDatabaseNameForUser(userId)
                    )
                        .fallbackToDestructiveMigration()
                        .build()
                        .also { userInstances[key] = it }
                }
            }
        }

        fun closeDatabaseForUser(userId: String?) {
            val key = if (userId.isNullOrBlank()) "default" else sanitizeUserId(userId)
            synchronized(this) {
                userInstances.remove(key)?.let { db ->
                    try {
                        if (db.isOpen) {
                            db.close()
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        fun deleteDatabaseForUser(context: Context, userId: String?) {
            closeDatabaseForUser(userId)
            val dbName = getDatabaseNameForUser(userId)
            try {
                context.applicationContext.deleteDatabase(dbName)
            } catch (_: Exception) {}
        }

        fun closeAllDatabases() {
            synchronized(this) {
                userInstances.values.forEach { db ->
                    try {
                        if (db.isOpen) {
                            db.close()
                        }
                    } catch (_: Exception) {}
                }
                userInstances.clear()
            }
        }

        fun resetDatabase(context: Context) {
            closeAllDatabases()
            try {
                context.applicationContext.deleteDatabase(DEFAULT_DB_NAME)
            } catch (_: Exception) {}
        }
    }
}
