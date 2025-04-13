package com.ketch.internal.download

import com.ketch.internal.utils.FileUtil.getUniqueId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class DownloadRequest(
    val url: String,
    val path: String,
    var fileName: String,
    val tag: String,
    val id: Int = getUniqueId(url, path, fileName),
    val headers: HashMap<String, String> = hashMapOf(),
    val metaData: String = "",
    val supportPauseResume: Boolean = true,
) {
    fun toJson(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromJson(jsonStr: String): DownloadRequest {
            return Json.decodeFromString(jsonStr)
        }
    }
}
