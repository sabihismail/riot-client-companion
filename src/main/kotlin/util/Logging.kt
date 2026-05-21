package util

import org.apache.commons.lang3.builder.RecursiveToStringStyle
import org.apache.commons.lang3.builder.ReflectionToStringBuilder

object Logging {
    private const val ANSI_RESET = "\u001B[0m"
    private const val ANSI_ERROR_RED = "\u001B[91m"
    private const val ANSI_WARNING_YELLOW = "\u001B[93m"
    private val ANSI_SET = setOf(ANSI_ERROR_RED, ANSI_WARNING_YELLOW)

    var logMode = LogType.INFO

    private val logged = hashSetOf<String>()

    fun log(obj: Any, logType: LogType, header: String? = null, ignorableDuplicate: Boolean = false) {
        if (logType < logMode) return

        val s = ReflectionToStringBuilder.reflectionToString(obj, RecursiveToStringStyle())

        var headerValue = header
        if (headerValue == null) {
            headerValue = obj::class.simpleName
        }

        log(s, logType, header = headerValue, ignorableDuplicate = ignorableDuplicate)
    }

    fun log(str: String, logType: LogType, header: String? = null, ignorableDuplicate: Boolean = false, carriageReturn: Int = -1, messageType: LogMessageType? = null) {
        if (logType < logMode) return
        val s = StringBuilder()
        if (logType == LogType.WARNING) {
            if (logged.contains(str)) return

            s.append(ANSI_WARNING_YELLOW + "WARNING: ")
        }

        if (logType == LogType.ERROR) {
            s.append(ANSI_ERROR_RED + "ERROR: ")
        }

        if (messageType != null) {
            s.append("[${messageType.name}] ")
        }

        if (!header.isNullOrBlank()) {
            s.append("$header: ")
        }

        s.append(str)

        if (ANSI_SET.any { it.contains(s.toString()) }) {
            s.append(ANSI_RESET)
        }

        val toStr = s.toString()
        if (ignorableDuplicate && toStr == logged.last()) return

        logged.add(str)

        if (carriageReturn != -1) {
            print(toStr + if (carriageReturn == 0) "\r" else "\n")
        } else {
            println(toStr)
        }
    }
}