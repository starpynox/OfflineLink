package com.offlinelink.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PeerEntity::class,
        MessageEntity::class,
        QueueItemEntity::class,
        SeenMessageEntity::class,
        FileTransferEntity::class,
        DiagnosticLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OfflineLinkDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun queueDao(): QueueDao
    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun diagnosticDao(): DiagnosticDao

    companion object {
        @Volatile
        private var instance: OfflineLinkDatabase? = null

        fun getInstance(context: Context): OfflineLinkDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineLinkDatabase::class.java,
                    "offlinelink.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
