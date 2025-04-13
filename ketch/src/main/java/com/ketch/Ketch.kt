package com.ketch

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ketch.internal.database.DatabaseInstance
import com.ketch.internal.database.DownloadDao
import com.ketch.internal.database.DownloadEntity
import com.ketch.internal.download.ApiResponseHeaderChecker
import com.ketch.internal.download.DownloadRequest
import com.ketch.internal.download.DownloadWorker
import com.ketch.internal.network.RetrofitInstance
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.DownloadLogger
import com.ketch.internal.utils.FileUtil
import com.ketch.internal.utils.UserAction
import com.ketch.internal.utils.WorkUtil
import com.ketch.internal.utils.toDownloadModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

interface GeneralDownloadStatusCallback {
    fun onStatusChanged(status: Status)
}

interface DownloadStatusCallback {
    //    fun onQueued(task: DownloadModel){}
    fun onStarted(task: DownloadModel) {}

    //    fun onProgress(task: DownloadModel){}
    fun onSuccess(task: DownloadModel) {}

    //    fun onCanceled(task: DownloadModel){}
    fun onFailed(task: DownloadModel) {}
//    fun onPaused(task: DownloadModel){}
}

/**
 * Ketch: Core singleton class client interacts with
 *
 * How to initialize [Ketch] instance?
 *
 * ```
 * // Simplest way to initialize:
 * Ketch.builder().build(context)
 *
 * // Sample to initialize the library inside application class
 * class MainApplication : Application() {
 *
 *     lateinit var ketch: Ketch
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         ketch = Ketch.builder()
 *             .setOkHttpClient(...) // optional
 *             .setDownloadConfig(DownloadConfig()) // optional
 *             .enableLogs(true) // optional, logs are off by default
 *             .setLogger(...) // optional, pass your own logger implementation
 *             .build(this)
 *     }
 *
 * }
 *
 * // To use the library
 * ketch.download(url, path, fileName) // download
 * ketch.pause(id) // pause download
 * ketch.resume(id) // resume download
 * ketch.retry(id) // retry download
 * ketch.cancel(id) // cancel download
 * ketch.clearDb(id) // clear database and delete file
 *
 * // To observe the downloads
 * lifecycleScope.launch {
 *    repeatOnLifecycle(Lifecycle.State.STARTED) {
 *       ketch.observeDownloads()
 *        .flowOn(Dispatchers.IO)
 *        .collect { downloadModelList ->
 *         // take appropriate action with observed list of [DownloadModel]
 *       }
 *    }
 * }
 *
 * ```
 *
 * JOURNEY OF SINGLE DOWNLOAD FILE:
 *
 * [Status.QUEUED] -> [Status.STARTED] -> [Status.PROGRESS] -> Download in progress
 * Terminating states: [Status.PAUSED], [Status.CANCELLED], [Status.FAILED], [Status.SUCCESS]
 *
 * @property context Application context
 * @property downloadConfig [DownloadConfig] to configure download related info
 * @property logger [Logger] implementation to print logs
 * @constructor Create empty Ketch
 */
@Suppress("TooManyFunctions")
class Ketch private constructor(
    private val context: Context,
    private var downloadConfig: DownloadConfig,
    private var logger: Logger,
    private var okHttpClient: OkHttpClient
) {

    private val mutex = Mutex()

    companion object {

        @Volatile
        private var ketchInstance: Ketch? = null

        fun builder() = Builder()

        class Builder {
            private var downloadConfig: DownloadConfig = DownloadConfig()
            private var logger: Logger = NoneLogger()
            private lateinit var okHttpClient: OkHttpClient

            /**
             * Set download config: It has no effect if using [setOkHttpClient] function
             * Pass timeout values inside okHttpClient itself
             *
             * @param config [DownloadConfig]
             */
            fun setDownloadConfig(config: DownloadConfig) = apply {
                this.downloadConfig = config
            }

            fun enableLogs(enable: Boolean) = apply {
                this.logger = if (enable) DownloadLogger() else NoneLogger()
            }

            fun setLogger(logger: Logger) = apply {
                this.logger = logger
            }

            fun setOkHttpClient(okHttpClient: OkHttpClient) = apply {
                this.okHttpClient = okHttpClient
            }

            @Synchronized
            fun build(context: Context): Ketch {
                if (ketchInstance == null) {

                    if (!::okHttpClient.isInitialized) {
                        okHttpClient = OkHttpClient
                            .Builder()
                            .connectTimeout(downloadConfig.connectTimeOutInMs, TimeUnit.MILLISECONDS)
                            .readTimeout(downloadConfig.readTimeOutInMs, TimeUnit.MILLISECONDS)
                            .build()
                    }

                    ketchInstance = Ketch(
                        context = context.applicationContext,
                        downloadConfig = downloadConfig,
                        logger = logger,
                        okHttpClient = okHttpClient
                    )
                }
                return ketchInstance!!
            }
        }
    }

    init {
        RetrofitInstance.getDownloadService(okHttpClient = okHttpClient)
    }

    private val downloadDao = DatabaseInstance.getInstance(context).downloadDao()
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val downloadManager = DownloadManager(
        downloadDao = downloadDao,
        workManager = workManager,
        downloadConfig = downloadConfig,
        logger = logger
    )

    /**
     * Download the content
     *
     * @param url Download url of the content
     * @param path Download path to store the downloaded file
     * @param fileName Name of the file to be downloaded
     * @param tag Optional tag for each download to group the download into category
     * @param metaData Optional metaData set for adding any extra download info, note that the Data cannot occupy more than 10240 bytes when serialized
     * @param headers Optional headers sent when making api call for file download
     * @param supportPauseResume Optional flag to enable pause and resume functionality
     * @return Unique Download ID associated with current download
     */
    fun download(
        url: String,
        path: String,
        fileName: String = FileUtil.getFileNameFromUrl(url),
        tag: String = "",
        metaData: String = "",
        headers: HashMap<String, String> = hashMapOf(),
        supportPauseResume: Boolean = true,
    ): Int {
        val downloadRequest = prepareDownloadRequest(
            url = url,
            path = path,
            fileName = fileName,
            tag = tag,
            headers = headers,
            metaData = metaData,
            supportPauseResume = supportPauseResume,
        )
        downloadManager.downloadAsync(downloadRequest)
        return downloadRequest.id
    }

    /**
     * Download the content synchronously, it will return the download id once download is entered in the queue
     *
     * @param url Download url of the content
     * @param path Download path to store the downloaded file
     * @param fileName Name of the file to be downloaded
     * @param tag Optional tag for each download to group the download into category
     * @param metaData Optional metaData set for adding any extra download info
     * @param headers Optional headers sent when making api call for file download
     * @return Unique Download ID associated with current download
     */
    suspend fun downloadSync(
        url: String,
        path: String,
        fileName: String = FileUtil.getFileNameFromUrl(url),
        tag: String = "",
        metaData: String = "",
        headers: HashMap<String, String> = hashMapOf(),
        supportPauseResume: Boolean = true,
    ): Int {
        val downloadRequest = mutex.withLock {
            prepareDownloadRequest(
                url = url,
                path = path,
                fileName = fileName,
                tag = tag,
                headers = headers,
                metaData = metaData,
                supportPauseResume = supportPauseResume,
            )
        }
        downloadManager.download(downloadRequest)
        return downloadRequest.id
    }
    suspend fun <T> cancelX(parameter: T) {
        when (parameter) {
            is Int -> downloadManager.cancel(parameter)
            is String -> downloadManager.cancel(parameter)
        }
    }

    suspend fun <T> pauseX(parameter: T) {
        when (parameter) {
            is Int -> downloadManager.pause(parameter)
            is String -> downloadManager.pause(parameter)
        }
    }

    suspend fun <T> retryX(parameter: T) {
        when (parameter) {
            is Int -> downloadManager.retry(parameter)
            is String -> downloadManager.retry(parameter)
        }
    }

    suspend fun <T> resumeX(parameter: T) {
        when (parameter) {
            is Int -> downloadManager.resume(parameter)
            is String -> downloadManager.resume(parameter)
        }
    }

    suspend fun <T> clearDbX(parameter: T, deleteFile: Boolean = false) {
        when (parameter) {
            is Int -> downloadManager.clearDb(parameter, deleteFile)
            is String -> downloadManager.clearDb(parameter, deleteFile)
        }
    }

    /**
     * Cancel download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun cancel(id: Int) {
        downloadManager.cancelAsync(id)
    }

    /**
     * Cancel downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun cancel(tag: String) {
        downloadManager.cancelAsync(tag)
    }

    /**
     * Cancel all the downloads
     *
     */
    fun cancelAll() {
        downloadManager.cancelAllAsync()
    }

    /**
     * Pause download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun pause(id: Int) {
        downloadManager.pauseAsync(id)
    }

    /**
     * Pause downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun pause(tag: String) {
        downloadManager.pauseAsync(tag)
    }

    /**
     * Pause all the downloads
     *
     */
    fun pauseAll() {
        downloadManager.pauseAllAsync()
    }

    /**
     * Resume download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun resume(id: Int) {
        downloadManager.resumeAsync(id)
    }

    /**
     * Resume downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun resume(tag: String) {
        downloadManager.resumeAsync(tag)
    }

    /**
     * Resume all the downloads
     *
     */
    fun resumeAll() {
        downloadManager.resumeAllAsync()
    }

    /**
     * Retry download with given [id]
     *
     * @param id Unique Download ID of the download
     */
    fun retry(id: Int) {
        downloadManager.retryAsync(id)
    }

    /**
     * Retry downloads with given [tag]
     *
     * @param tag Tag associated with the download
     */
    fun retry(tag: String) {
        downloadManager.retryAsync(tag)
    }

    /**
     * Retry all the downloads
     *
     */
    fun retryAll() {
        downloadManager.retryAllAsync()
    }

    /**
     * Clear all entries from database and delete all the files
     *
     * @param deleteFile delete the actual file from the system
     */
    fun clearAllDb(deleteFile: Boolean = true) {
        downloadManager.clearAllDbAsync(deleteFile)
    }

    /**
     * Clear entries from database and delete files on or before [timeInMillis]
     *
     * @param timeInMillis timestamp in millisecond
     * @param deleteFile delete the actual file from the system
     */
    fun clearDb(timeInMillis: Long, deleteFile: Boolean = true) {
        downloadManager.clearDbAsync(timeInMillis, deleteFile)
    }

    /**
     * Clear entry from database and delete file with given [id]
     *
     * @param id Unique Download ID of the download
     * @param deleteFile delete the actual file from the system
     */
    fun clearDb(id: Int, deleteFile: Boolean = true) {
        downloadManager.clearDbAsync(id, deleteFile)
    }

    /**
     * Clear entries from database and delete files with given [tag]
     *
     * @param tag Tag associated with the download
     * @param deleteFile delete the actual file from the system
     */
    fun clearDb(tag: String, deleteFile: Boolean = true) {
        downloadManager.clearDbAsync(tag, deleteFile)
    }

    /**
     * Suspend function to make headers only api call to get and compare ETag string of content
     *
     * @param url Download Url
     * @param headers Optional headers associated with url of download request
     * @param eTag Existing ETag of content
     * @return Boolean to compare existing and newly fetched ETag of the content
     */
    suspend fun isContentValid(
        url: String,
        headers: HashMap<String, String> = hashMapOf(),
        eTag: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            ApiResponseHeaderChecker(url, RetrofitInstance.getDownloadService(), headers)
                .getHeaderValue(DownloadConst.ETAG_HEADER) == eTag
        }

    /**
     * Suspend function to make headers only api call to get length of content in bytes
     *
     * @param url Download Url
     * @param headers Optional headers associated with url of download request
     * @return Length of content to be downloaded in bytes
     */
    suspend fun getContentLength(
        url: String,
        headers: HashMap<String, String> = hashMapOf()
    ): Long =
        withContext(Dispatchers.IO) {
            ApiResponseHeaderChecker(url, RetrofitInstance.getDownloadService(), headers)
                .getHeaderValue(DownloadConst.CONTENT_LENGTH)?.toLong() ?: 0
        }

    private fun prepareDownloadRequest(
        url: String,
        path: String,
        fileName: String,
        tag: String,
        headers: HashMap<String, String>,
        metaData: String,
        supportPauseResume: Boolean,
    ): DownloadRequest {
        require(url.isNotEmpty() && path.isNotEmpty() && fileName.isNotEmpty()) {
            "Missing ${if (url.isEmpty()) "url" else if (path.isEmpty()) "path" else "fileName"}"
        }
        File(path).mkdirs()
        val downloadRequest = DownloadRequest(
            url = url,
            path = path,
            fileName = fileName,
            tag = tag,
            headers = headers,
            metaData = metaData,
            supportPauseResume = supportPauseResume,
        )
        val downloadEntity = runBlocking(Dispatchers.IO) { downloadDao.find(downloadRequest.id) }
        if (downloadEntity != null) { //If there is already added task
            return downloadRequest
        }

        val newFileName: String = if (downloadConfig.renameWhenConflict) {
            // This will make sure each file name is unique.
            FileUtil.resolveNamingConflicts(fileName, path)
        } else fileName
        // This will create a temp file which will be renamed after successful download.
        FileUtil.createTempFileIfNotExists(path, newFileName)
        downloadRequest.fileName = newFileName
        return downloadRequest
    }

    /**
     * Observe all downloads
     *
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloads(): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityFlow().distinctUntilChanged().map { entityList ->
            entityList.toDownloadModel()
        }
    }

    /**
     * Observe download with given [id]
     *
     * @param id Unique Download ID of the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadById(id: Int): Flow<DownloadModel?> {
        return downloadDao.getEntityByIdFlow(id).distinctUntilChanged().map { entity ->
            entity?.toDownloadModel()
        }
    }

    /**
     * Observe downloads with given [ids]
     *
     * @param ids List of ids associated with the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadsByIds(ids: List<Int>): Flow<List<DownloadModel?>> {
        return downloadDao.getAllEntityByIdsFlow(ids).distinctUntilChanged().map { entityList ->
            ids.map { id ->
                entityList.find { it?.id == id }?.toDownloadModel()
            }
        }
    }

    /**
     * Observe downloads with given [tag]
     *
     * @param tag Tag associated with the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadsByTag(tag: String): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityByTagFlow(tag).distinctUntilChanged().map { entityList ->
            entityList.toDownloadModel()
        }
    }

    /**
     * Observe downloads with given [tags]
     *
     * @param tags List of tags associated with the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadsByTags(tags: List<String>): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityByTagsFlow(tags).distinctUntilChanged().map { entityList ->
            entityList.toDownloadModel()
        }
    }

    /**
     * Observe downloads with given [status]
     *
     * @param status Status associated with the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadsByStatus(status: Status): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityByStatusFlow(status).distinctUntilChanged()
            .map { entityList ->
                entityList.toDownloadModel()
            }
    }

    /**
     * Observe downloads with given [statuses]
     *
     * @param statuses List of statuses associated with the download
     * @return [Flow] of List of [DownloadModel]
     */
    fun observeDownloadsByStatuses(statuses: List<Status>): Flow<List<DownloadModel>> {
        return downloadDao.getAllEntityByStatusesFlow(statuses).distinctUntilChanged()
            .map { entityList ->
                entityList.toDownloadModel()
            }
    }

    /**
     * Suspend function to get list of all Downloads
     *
     * @return List of [DownloadModel]
     */
    suspend fun getAllDownloads(): List<DownloadModel> {
        return downloadDao.getAllEntity().toDownloadModel()
    }

    /**
     * Suspend function to get download model by id
     *
     * @param id
     * @return [DownloadModel] if present else null
     */
    suspend fun getDownloadModelById(id: Int): DownloadModel? {
        return downloadDao.find(id)?.toDownloadModel()
    }

    /**
     * Suspend function to get download model by list of ids
     *
     * @param ids
     * @return List of [DownloadModel]
     */
    suspend fun getDownloadModelByIds(ids: List<Int>): List<DownloadModel?> {
        val entityList = downloadDao.getAllEntityByIds(ids)
        return ids.map { id ->
            entityList.find { it?.id == id }?.toDownloadModel()
        }
    }

    /**
     * Suspend function to get download model by tag
     *
     * @param tag
     * @return [DownloadModel] if present else null
     */
    suspend fun getDownloadModelByTag(tag: String): List<DownloadModel> {
        return downloadDao.getAllEntityByTag(tag).toDownloadModel()
    }

    /**
     * Suspend function to get download model by list of tags
     *
     * @param tags
     * @return List of [DownloadModel]
     */
    suspend fun getDownloadModelByTags(tags: List<String>): List<DownloadModel> {
        return downloadDao.getAllEntityByTags(tags).toDownloadModel()
    }

    /**
     * Suspend function to get download model by status
     *
     * @param status
     * @return [DownloadModel] if present else null
     */
    suspend fun getDownloadModelByStatus(status: Status): List<DownloadModel> {
        return downloadDao.getAllEntityByStatus(status).toDownloadModel()
    }

    /**
     * Suspend function to get download model by list of statuses
     *
     * @param statuses
     * @return List of [DownloadModel]
     */
    suspend fun getDownloadModelByStatuses(statuses: List<Status>): List<DownloadModel> {
        return downloadDao.getAllEntityByStatuses(statuses).toDownloadModel()
    }

    private fun List<DownloadEntity>.toDownloadModel(): List<DownloadModel> = map { entity ->
        entity.toDownloadModel()
    }


    internal class DownloadManager(
        val downloadDao: DownloadDao,
        val workManager: WorkManager,
        private val downloadConfig: DownloadConfig,
        private val logger: Logger
    ) {

        private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            logger.log(
                msg = "Exception in DownloadManager Scope: ${throwable.message}"
            )
        }

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

        init {
            suspend fun findDownloadEntityFromUUID(uuid: UUID): DownloadEntity? {
                return downloadDao.getAllEntity().find { it.uuid == uuid.toString() }
            }
            workManager.pruneWork()
//            scope.launch {
//                // remove work if is null
//                workManager.getWorkInfosByTagFlow(DownloadConst.TAG_DOWNLOAD)
//                    .flowOn(Dispatchers.IO).take(1).collect {workInfos ->
//                        for (workInfo in workInfos) {
//                            if ( findDownloadEntityFromUUID(workInfo.id) == null) {
//                                workManager.cancelWorkById(workInfo.id)
//                            }
//                        }
//                }
//            }
            if (logger !is NoneLogger) {
                scope.launch {
                    // Observe work infos, only for logging purpose
                    var lastWorkInfos: List<WorkInfo> = emptyList()
                    workManager.getWorkInfosByTagFlow(DownloadConst.TAG_DOWNLOAD)
                        .flowOn(Dispatchers.IO).distinctUntilChanged().collectLatest { workInfos ->
                            for (workInfo in workInfos.filter { workInfo ->
                                lastWorkInfos.find {
                                    it.id == workInfo.id && it.state == workInfo.state
                                            && it.progress == workInfo.progress
                                } == null
                            }) {
                                when (workInfo.state) {
                                    WorkInfo.State.ENQUEUED -> {
                                        val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                        logger.log(
                                            msg = "Download Queued. FileName: ${downloadEntity?.fileName}, " +
                                                    "ID: ${downloadEntity?.id}"
                                        )
                                    }

                                    WorkInfo.State.RUNNING -> {
                                        val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                        when (workInfo.progress.getString(DownloadConst.KEY_STATE)) {
                                            DownloadConst.STARTED ->
                                                logger.log(
                                                    msg = "Download Started. FileName: ${downloadEntity?.fileName}, " +
                                                            "ID: ${downloadEntity?.id}, " +
                                                            "Size in bytes: ${downloadEntity?.totalBytes}"
                                                )

                                            DownloadConst.PROGRESS ->
                                                logger.log(
                                                    msg = "Download in Progress. FileName: ${downloadEntity?.fileName}, " +
                                                            "ID: ${downloadEntity?.id}, " +
                                                            "Size in bytes: ${downloadEntity?.totalBytes}, " +
                                                            "downloadPercent: ${
                                                                if (downloadEntity != null && downloadEntity.totalBytes.toInt() != 0) {
                                                                    ((downloadEntity.downloadedBytes * 100) / downloadEntity.totalBytes).toInt()
                                                                } else {
                                                                    0
                                                                }
                                                            }%, " +
                                                            "downloadSpeedInBytesPerMilliSeconds: ${downloadEntity?.speedInBytePerMs} b/ms"
                                                )

                                        }
                                    }

                                    WorkInfo.State.SUCCEEDED -> {
                                        val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                        logger.log(
                                            msg = "Download Success. FileName: ${downloadEntity?.fileName}, " +
                                                    "ID: ${downloadEntity?.id}"
                                        )
                                    }

                                    WorkInfo.State.FAILED -> {
                                        val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                        logger.log(
                                            msg = "Download Failed. FileName: ${downloadEntity?.fileName}, " +
                                                    "ID: ${downloadEntity?.id}, " +
                                                    "Reason: ${downloadEntity?.failureReason}"
                                        )
                                    }

                                    WorkInfo.State.CANCELLED -> {
                                        val downloadEntity = findDownloadEntityFromUUID(workInfo.id)
                                        if (downloadEntity?.userAction == UserAction.PAUSE) {
                                            logger.log(
                                                msg = "Download Paused. FileName: ${downloadEntity.fileName}, " +
                                                        "ID: ${downloadEntity.id}"
                                            )
                                        } else if (downloadEntity?.userAction == UserAction.CANCEL) {
                                            logger.log(
                                                msg = "Download Cancelled. FileName: ${downloadEntity.fileName}, " +
                                                        "ID: ${downloadEntity.id}"
                                            )
                                        }
                                    }

                                    WorkInfo.State.BLOCKED -> {} // no use case
                                }
                            }
                        }
                }
            }
        }

        suspend fun download(downloadRequest: DownloadRequest) {

            val inputDataBuilder = Data.Builder()
                .putString(DownloadConst.KEY_DOWNLOAD_REQUEST, downloadRequest.toJson())

            val inputData = inputDataBuilder.build()

            val constraints = Constraints
                .Builder()
                .build()

            val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(inputData)
                .addTag(DownloadConst.TAG_DOWNLOAD)
                .setConstraints(constraints)
                .build()

            // Checks if download id already present in database
            if (downloadDao.find(downloadRequest.id) != null) {

                downloadDao.find(downloadRequest.id)?.copyForModification(
                    userAction = UserAction.START
                )?.let { downloadDao.update(it) }

                val downloadEntity = downloadDao.find(downloadRequest.id)

                // In case new download request is generated for already existing id in database
                // and work is not in progress, replace the uuid in database

                if (downloadEntity != null &&
                    downloadEntity.uuid != downloadWorkRequest.id.toString() &&
                    downloadEntity.status != Status.QUEUED &&
                    downloadEntity.status != Status.PROGRESS &&
                    downloadEntity.status != Status.STARTED
                ) {
                    downloadDao.find(downloadRequest.id)?.copyForModification(
                        uuid = downloadWorkRequest.id.toString(),
                        status = Status.QUEUED,
                    )?.let { downloadDao.update(it) }
                }
            } else {
                downloadDao.insert(
                    DownloadEntity(
                        url = downloadRequest.url,
                        path = downloadRequest.path,
                        fileName = downloadRequest.fileName,
                        tag = downloadRequest.tag,
                        id = downloadRequest.id,
                        headersJson = WorkUtil.hashMapToJson(downloadRequest.headers),
                        timeQueued = System.currentTimeMillis(),
                        status = Status.QUEUED,
                        uuid = downloadWorkRequest.id.toString(),
                        lastModified = System.currentTimeMillis(),
                        userAction = UserAction.START,
                        metaData = downloadRequest.metaData
                    )
                )
            }

            workManager.enqueueUniqueWork(
                downloadRequest.id.toString(),
                ExistingWorkPolicy.KEEP,
                downloadWorkRequest
            )
        }

        suspend fun resume(id: Int) {
            downloadDao.find(id)?.copyForModification(
                userAction = UserAction.RESUME
            )?.let {
                if ((it.status != Status.PROGRESS && it.status != Status.SUCCESS)) {
                    downloadDao.update(it)
                    download(
                        DownloadRequest(
                            url = it.url,
                            path = it.path,
                            fileName = it.fileName,
                            tag = it.tag,
                            id = it.id,
                            headers = WorkUtil.jsonToHashMap(it.headersJson),
                            metaData = it.metaData
                        )
                    )
                }
            }
        }

        suspend fun cancel(id: Int) {
            downloadDao.find(id)?.copyForModification(
                userAction = UserAction.CANCEL,
            )?.let {
                downloadDao.update(it)
                if (it.status == Status.PAUSED ||
                    it.status == Status.FAILED
                ) { // Edge Case: When user cancel the download in pause or fail (terminating) state as work is already cancelled.

                    downloadDao.find(it.id)?.copyForModification(
                        status = Status.CANCELLED,
                    )?.let { downloadDao.update(it) }
                    FileUtil.deleteFileIfExists(it.path, it.fileName)
                }
            }
            workManager.cancelUniqueWork(id.toString())
        }

        suspend fun pause(id: Int) {
            downloadDao.find(id)?.copyForModification(
                userAction = UserAction.PAUSE
            )?.let { downloadDao.update(it) }
            workManager.cancelUniqueWork(id.toString())
        }

        suspend fun retry(id: Int) {
            downloadDao.find(id)?.copyForModification(
                userAction = UserAction.RETRY
            )?.let {
                downloadDao.update(it)
                download(
                    DownloadRequest(
                        url = it.url,
                        path = it.path,
                        fileName = it.fileName,
                        tag = it.tag,
                        id = it.id,
                        headers = WorkUtil.jsonToHashMap(it.headersJson),
                        metaData = it.metaData
                    )
                )
            }
        }

        suspend fun clearDb(id: Int, deleteFile: Boolean) {
            workManager.cancelUniqueWork(id.toString())
            if (deleteFile) {
                val downloadEntity = downloadDao.find(id)
                val path = downloadEntity?.path
                val fileName = downloadEntity?.fileName
                if (path != null && fileName != null)
                    FileUtil.deleteFileIfExists(path, fileName)
            }
            downloadDao.remove(id)
        }

        suspend fun resume(tag: String) {
            downloadDao.getAllEntityByTag(tag).forEach { resume(it.id) }
        }

        suspend fun cancel(tag: String) {
            downloadDao.getAllEntityByTag(tag).forEach { cancel(it.id) }
        }

        suspend fun pause(tag: String) {
            downloadDao.getAllEntityByTag(tag).forEach { pause(it.id) }
        }

        suspend fun retry(tag: String) {
            downloadDao.getAllEntityByTag(tag).forEach { retry(it.id) }
        }

        suspend fun clearDb(tag: String, deleteFile: Boolean) {
            downloadDao.getAllEntityByTag(tag).forEach { clearDb(it.id, deleteFile) }
        }


        fun resumeAsync(id: Int) {
            scope.launch {
                resume(id)
            }
        }

        fun resumeAsync(tag: String) {
            scope.launch {
                downloadDao.getAllEntityByTag(tag).forEach {
                    resume(it.id)
                }
            }
        }

        fun resumeAllAsync() {
            scope.launch {
                downloadDao.getAllEntity().forEach {
                    resume(it.id)
                }
            }
        }

        fun cancelAsync(id: Int) {
            scope.launch {
                cancel(id)
            }
        }

        fun cancelAsync(tag: String) {
            scope.launch {
                downloadDao.getAllEntityByTag(tag).forEach {
                    cancel(it.id)
                }
            }
        }

        fun cancelAllAsync() {
            scope.launch {
                downloadDao.getAllEntity().forEach {
                    cancel(it.id)
                }
            }
        }

        fun pauseAsync(id: Int) {
            scope.launch {
                pause(id)
            }
        }

        fun pauseAsync(tag: String) {
            scope.launch {
                downloadDao.getAllEntityByTag(tag).forEach {
                    pause(it.id)
                }
            }
        }

        fun pauseAllAsync() {
            scope.launch {
                downloadDao.getAllEntity().forEach {
                    pause(it.id)
                }
            }
        }

        fun retryAsync(id: Int) {
            scope.launch {
                retry(id)
            }
        }

        fun retryAsync(tag: String) {
            scope.launch {
                downloadDao.getAllEntityByTag(tag).forEach {
                    retry(it.id)
                }
            }
        }

        fun retryAllAsync() {
            scope.launch {
                downloadDao.getAllEntity().forEach {
                    retry(it.id)
                }
            }
        }

        fun clearDbAsync(id: Int, deleteFile: Boolean) {
            scope.launch {
                clearDb(id, deleteFile)
            }
        }

        fun clearDbAsync(tag: String, deleteFile: Boolean) {
            scope.launch {
                downloadDao.getAllEntityByTag(tag).forEach {
                    clearDb(it.id, deleteFile)
                }
            }
        }

        fun clearDbAsync(timeInMillis: Long, deleteFile: Boolean) {
            scope.launch {
                downloadDao.getEntityTillTime(timeInMillis).forEach {
                    clearDb(it.id, deleteFile)
                }
            }
        }

        fun clearAllDbAsync(deleteFile: Boolean) {
            scope.launch {
                downloadDao.getAllEntity().forEach {
                    clearDb(it.id, deleteFile)
                }
            }
        }

        fun downloadAsync(downloadRequest: DownloadRequest) {
            scope.launch {
                download(downloadRequest)
            }
        }
    }
}