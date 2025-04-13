package com.ketch.internal.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ketch.Status
import com.ketch.internal.database.DatabaseInstance
import com.ketch.internal.network.RetrofitInstance
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.ExceptionConst
import com.ketch.internal.utils.FileUtil
import com.ketch.internal.utils.UserAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

internal class DownloadWorker(
    private val context: Context,
    private val workerParameters: WorkerParameters
) :
    CoroutineWorker(context, workerParameters) {

    companion object {
        private const val MAX_PERCENT = 100
    }

    private val downloadDao = DatabaseInstance.getInstance(context).downloadDao()

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun doWork(): Result {

        val downloadRequest = DownloadRequest.fromJson(
            inputData.getString(DownloadConst.KEY_DOWNLOAD_REQUEST)
                ?: return Result.failure(
                    workDataOf(ExceptionConst.KEY_EXCEPTION to ExceptionConst.EXCEPTION_FAILED_DESERIALIZE)
                )
        )

        val id = downloadRequest.id
        val url = downloadRequest.url
        val dirPath = downloadRequest.path
        val fileName = downloadRequest.fileName
        val headers = downloadRequest.headers
        val supportPauseResume = downloadRequest.supportPauseResume // in case of false, we will not store total length info in DB


        val downloadService = RetrofitInstance.getDownloadService()

        return try {
            val latestETag =
                ApiResponseHeaderChecker(downloadRequest.url, downloadService, headers)
                    .getHeaderValue(DownloadConst.ETAG_HEADER) ?: ""

            val existingETag = downloadDao.find(id)?.eTag ?: ""

            if (latestETag != existingETag) {
                FileUtil.deleteFileIfExists(path = dirPath, name = fileName)
                FileUtil.createTempFileIfNotExists(path = dirPath, fileName = fileName)
                downloadDao.find(id)?.copyForModification(
                    eTag = latestETag,
                )?.let { downloadDao.update(it) }
            }

            var progressPercentage = -1

            val totalLength = DownloadTask(
                url = url,
                path = dirPath,
                fileName = fileName,
                supportPauseResume = supportPauseResume,
                downloadService = downloadService
            ).download(
                headers = headers,
                onStart = { length ->

                    downloadDao.find(id)?.copyForModification(
                        totalBytes = length,
                        status = Status.STARTED,
                    )?.let { downloadDao.update(it) }

                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.STARTED
                        )
                    )
                },
                onProgress = { downloadedBytes, length, speed ->

                    val progress = if (length != 0L) {
                        ((downloadedBytes * 100) / length).toInt()
                    } else {
                        0
                    }

                    if (progressPercentage != progress) {

                        progressPercentage = progress

                        downloadDao.find(id)?.copyForModification(
                            downloadedBytes = downloadedBytes,
                            speedInBytePerMs = speed,
                            status = Status.PROGRESS,
                        )?.let { downloadDao.update(it) }

                    }

                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.PROGRESS,
                            DownloadConst.KEY_PROGRESS to progress
                        )
                    )
                }
            )

            downloadDao.find(id)?.copyForModification(
                totalBytes = totalLength,
                status = Status.SUCCESS,
            )?.let { downloadDao.update(it) }

            Result.success()
        } catch (e: Exception) {
            GlobalScope.launch {
                if (e is CancellationException) {
                    if (downloadDao.find(id)?.userAction == UserAction.PAUSE) {
                        downloadDao.find(id)?.copyForModification(
                            status = Status.PAUSED,
                        )?.let { downloadDao.update(it) }
                    } else {
                        downloadDao.find(id)?.copyForModification(
                            status = Status.CANCELLED,
                        )?.let { downloadDao.update(it) }
                        FileUtil.deleteFileIfExists(dirPath, fileName)
                    }
                } else {
                    downloadDao.find(id)?.copyForModification(
                        status = Status.FAILED,
                        failureReason = e.message ?: "",
                    )?.let { downloadDao.update(it) }
                }
            }
            Result.failure(
                workDataOf(ExceptionConst.KEY_EXCEPTION to e.message)
            )
        }

    }

}