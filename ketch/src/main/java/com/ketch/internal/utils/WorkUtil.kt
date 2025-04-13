package com.ketch.internal.utils

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object WorkUtil {

    fun hashMapToJson(headers: HashMap<String, String>): String {
        if (headers.isEmpty()) return ""
        return Json.encodeToString(headers)
    }

    fun jsonToHashMap(jsonString: String): HashMap<String, String> {
        if (jsonString.isEmpty()) return hashMapOf()
        return Json.decodeFromString(jsonString)
    }
}
