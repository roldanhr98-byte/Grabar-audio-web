package com.audiovault.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun originToString(v: AudioOrigin): String = v.name
    @TypeConverter fun stringToOrigin(v: String): AudioOrigin = AudioOrigin.valueOf(v)
    @TypeConverter fun statusToString(v: CaptureStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): CaptureStatus = CaptureStatus.valueOf(v)
    @TypeConverter fun dlToString(v: DownloadState): String = v.name
    @TypeConverter fun stringToDl(v: String): DownloadState = DownloadState.valueOf(v)
    @TypeConverter fun ssToString(v: SessionState): String = v.name
    @TypeConverter fun stringToSs(v: String): SessionState = SessionState.valueOf(v)
    @TypeConverter fun srcToString(v: CaptureSource): String = v.name
    @TypeConverter fun stringToSrc(v: String): CaptureSource = CaptureSource.valueOf(v)
}

@Database(
    entities = [
        AudioItemEntity::class,
        HistoryEntity::class,
        BookmarkEntity::class,
        DownloadEntity::class,
        CaptureSessionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audioItems(): AudioItemDao
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun downloads(): DownloadDao
    abstract fun sessions(): CaptureSessionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "audiovault.db")
                .build()
    }
}
