package com.ketch

interface Logger {
    companion object {
        const val TAG = "KetchLogs"
    }

    fun log(
        tag: String? = TAG,
        msg: String? = "",
        tr: Throwable? = null,
        type: LogType = LogType.DEBUG
    )
}

internal class NoneLogger : Logger {
    override fun log(tag: String?, msg: String?, tr: Throwable?, type: LogType) {}
}

enum class LogType {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
