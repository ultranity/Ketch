package com.ketch.notification

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NotificationConfig(
    val enabled: Boolean = NotificationConst.DEFAULT_VALUE_NOTIFICATION_ENABLED,
    val channelName: String = NotificationConst.DEFAULT_VALUE_NOTIFICATION_CHANNEL_NAME,
    val channelDescription: String = NotificationConst.DEFAULT_VALUE_NOTIFICATION_CHANNEL_DESCRIPTION,
    val importance: Int = NotificationConst.DEFAULT_VALUE_NOTIFICATION_CHANNEL_IMPORTANCE,
    val showSpeed: Boolean = true,
    val showSize: Boolean = true,
    val showTime: Boolean = true,
    val smallIcon: Int
) {
    fun toJson(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromJson(jsonStr: String): NotificationConfig {
            if (jsonStr.isEmpty()) {
                return NotificationConfig(smallIcon = NotificationConst.DEFAULT_VALUE_NOTIFICATION_SMALL_ICON)
            }
            return Json.decodeFromString(jsonStr)
        }

    }
}