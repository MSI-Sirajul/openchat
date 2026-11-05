package com.msi.openchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.msi.openchat.data.local.dao.ConversationDao
import com.msi.openchat.data.local.dao.MessageDao
import com.msi.openchat.data.local.entity.ConversationEntity
import com.msi.openchat.data.local.entity.MessageEntity

/**
 * Main Room Database for local caching of chat history and conversations.
 * Version 4 includes per-conversation monotonic sequence ordering and outbox state tracking.
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class OpenChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        private const val DB_NAME = "openchat_v4.db"

        @Volatile
        private var INSTANCE: OpenChatDatabase? = null

        fun getInstance(context: Context): OpenChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OpenChatDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun resetDatabase(context: Context) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                try {
                    context.applicationContext.deleteDatabase(DB_NAME)
                } catch (_: Exception) {}
            }
        }
    }
}
