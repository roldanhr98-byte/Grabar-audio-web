package com.audiovault.browser.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioItemDao {
    @Query("SELECT * FROM audio_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AudioItemEntity>>

    @Query("SELECT * FROM audio_items ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AudioItemEntity>>

    @Query("SELECT * FROM audio_items WHERE id = :id")
    suspend fun get(id: Long): AudioItemEntity?

    @Query("SELECT * FROM audio_items WHERE id = :id")
    fun observe(id: Long): Flow<AudioItemEntity?>

    @Query("SELECT * FROM audio_items WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<AudioItemEntity>

    @Query("SELECT * FROM audio_items WHERE sha256 = :sha LIMIT 1")
    suspend fun findBySha(sha: String): AudioItemEntity?

    @Query("SELECT fileName FROM audio_items WHERE folder = :folder")
    suspend fun fileNamesInFolder(folder: String): List<String>

    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM audio_items")
    fun observeTotalSize(): Flow<Long>

    @Insert
    suspend fun insert(item: AudioItemEntity): Long

    @Update
    suspend fun update(item: AudioItemEntity)

    @Delete
    suspend fun delete(item: AudioItemEntity)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 500")
    fun observe(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 1")
    suspend fun last(): HistoryEntity?

    @Insert
    suspend fun insert(e: HistoryEntity)

    @Query("UPDATE history SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observe(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun find(url: String): BookmarkEntity?

    @Query("SELECT COUNT(*) FROM bookmarks WHERE url = :url")
    fun observeIsBookmarked(url: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observe(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE systemDownloadId = :sysId LIMIT 1")
    suspend fun findBySystemId(sysId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' OR state = 'RUNNING'")
    suspend fun active(): List<DownloadEntity>

    @Insert
    suspend fun insert(e: DownloadEntity): Long

    @Update
    suspend fun update(e: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE state = 'DONE' OR state = 'FAILED'")
    suspend fun clearFinished()
}

@Dao
interface CaptureSessionDao {
    @Query("SELECT * FROM capture_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CaptureSessionEntity>>

    @Query("SELECT * FROM capture_sessions WHERE state IN ('RECORDING','PAUSED')")
    suspend fun openSessions(): List<CaptureSessionEntity>

    @Query("SELECT * FROM capture_sessions WHERE state = 'INTERRUPTED'")
    suspend fun interrupted(): List<CaptureSessionEntity>

    @Query("SELECT * FROM capture_sessions WHERE id = :id")
    suspend fun get(id: String): CaptureSessionEntity?

    @Query("SELECT cachePath FROM capture_sessions")
    suspend fun allPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: CaptureSessionEntity)

    @Query("UPDATE capture_sessions SET bytesWritten = :bytes, updatedAt = :now, state = :state WHERE id = :id")
    suspend fun progress(id: String, bytes: Long, now: Long, state: SessionState)

    @Query("DELETE FROM capture_sessions WHERE id = :id")
    suspend fun delete(id: String)
}
