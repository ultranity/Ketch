package com.ketch.internal.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ketch.Status
import com.ketch.internal.utils.UserAction

@Entity(
    tableName = "downloads"
)
internal data class DownloadEntity(
    @PrimaryKey
    var id: Int = 0,
    var url: String = "",
    var path: String = "",
    var fileName: String = "",
    var tag: String = "",
    var headersJson: String = "",
    var timeQueued: Long = 0,
    var status: Status = Status.DEFAULT,
    var totalBytes: Long = 0,
    var downloadedBytes: Long = 0,
    var speedInBytePerMs: Float = 0f,
    var uuid: String = "",
    var lastModified: Long = 0,
    var eTag: String = "",
    var userAction: UserAction = UserAction.DEFAULT,
    var metaData: String = "",
    var failureReason: String = ""
) {
    fun copyForModification(
        url: String = this.url,
        path: String = this.path,
        fileName: String = this.fileName,
        tag: String = this.tag,
        status: Status = this.status,
        totalBytes: Long = this.totalBytes,
        downloadedBytes: Long = this.downloadedBytes,
        speedInBytePerMs: Float = this.speedInBytePerMs,
        uuid: String = this.uuid,
        eTag: String = this.eTag,
        userAction: UserAction = this.userAction,
        metaData: String = this.metaData,
        failureReason: String = this.failureReason,
        lastModified: Long = System.currentTimeMillis(),
    ) = DownloadEntity(
        id = this.id,
        headersJson = this.headersJson,
        timeQueued = this.timeQueued,
        url = url,
        path = path,
        fileName = fileName,
        tag = tag,
        status = status,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        speedInBytePerMs = speedInBytePerMs,
        uuid = uuid,
        lastModified = lastModified,
        eTag = eTag,
        userAction = userAction,
        metaData = metaData,
        failureReason = failureReason
    )
}
