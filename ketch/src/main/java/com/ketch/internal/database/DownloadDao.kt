package com.ketch.internal.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ketch.Status
import kotlinx.coroutines.flow.Flow

@Dao
internal interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity)

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun find(id: Int): DownloadEntity?

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("DELETE FROM downloads WHERE lastModified <= :timeMillis")
    suspend fun removeTillTime(timeMillis: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun remove(id: Int)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun removeByIds(ids: List<Int>)

    @Query("DELETE FROM downloads WHERE tag = :tag")
    suspend fun removeByTag(tag: String)

    @Query("DELETE FROM downloads WHERE tag IN (:tags)")
    suspend fun removeByTags(tags: List<String>)

    @Query("DELETE FROM downloads WHERE status = :status")
    suspend fun removeByStatus(status: Status)

    @Query("DELETE FROM downloads WHERE status IN (:statuses)")
    suspend fun removeByStatuses(statuses: List<Status>)

    @Query("SELECT * FROM downloads ORDER BY timeQueued ASC")
    fun getAllEntityFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE lastModified <= :timeMillis ORDER BY timeQueued ASC")
    fun getEntityTillTimeFlow(timeMillis: Long): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id ORDER BY timeQueued ASC")
    fun getEntityByIdFlow(id: Int): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id IN (:ids) ORDER BY timeQueued ASC")
    fun getAllEntityByIdsFlow(ids: List<Int>): Flow<List<DownloadEntity?>>

    @Query("SELECT * FROM downloads WHERE tag = :tag ORDER BY timeQueued ASC")
    fun getAllEntityByTagFlow(tag: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE tag IN (:tags) ORDER BY timeQueued ASC")
    fun getAllEntityByTagsFlow(tags: List<String>): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY timeQueued ASC")
    fun getAllEntityByStatusFlow(status: Status): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY timeQueued ASC")
    fun getAllEntityByStatusesFlow(statuses: List<Status>): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY timeQueued ASC")
    suspend fun getAllEntity(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE lastModified <= :timeMillis ORDER BY timeQueued ASC")
    suspend fun getEntityTillTime(timeMillis: Long): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id IN (:ids) ORDER BY timeQueued ASC")
    suspend fun getAllEntityByIds(ids: List<Int>): List<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE tag = :tag ORDER BY timeQueued ASC")
    suspend fun getAllEntityByTag(tag: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE tag IN (:tags) ORDER BY timeQueued ASC")
    suspend fun getAllEntityByTags(tags: List<String>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY timeQueued ASC")
    suspend fun getAllEntityByStatus(status: Status): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY timeQueued ASC")
    suspend fun getAllEntityByStatuses(statuses: List<Status>): List<DownloadEntity>
}
