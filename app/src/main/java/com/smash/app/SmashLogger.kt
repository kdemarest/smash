package com.smash.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Logging utility for smash.
 * Writes timestamped entries to smash.log in app-private storage.
 * 
 * Format: yyyy-MM-dd-HH-mm-ss [level] message
 */
object SmashLogger {

    private const val LOG_FILENAME = "smash.log"
    private val lock = ReentrantLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    enum class Level(val tag: String) {
        INFO("[info]"),
        WARNING("[warning]"),
        ERROR("[error]"),
        SMS("[SMS]")
    }

    private var logFile: File? = null

    /**
     * Initialize the logger with app context.
     * Must be called before any logging.
     */
    fun init(context: Context) {
        lock.withLock {
            logFile = File(context.filesDir, LOG_FILENAME)
        }
    }

    /**
     * Log an info message.
     */
    fun info(message: String) {
        log(Level.INFO, message)
    }

    /**
     * Log an SMS message.
     */
    fun sms(message: String) {
        log(Level.SMS, message)
    }

    /**
     * Log a warning message.
     */
    fun warning(message: String) {
        log(Level.WARNING, message)
    }

    /**
     * Log an error message.
     */
    fun error(message: String) {
        log(Level.ERROR, message)
    }

    /**
     * Log an error with exception.
     */
    fun error(message: String, throwable: Throwable) {
        log(Level.ERROR, "$message: ${throwable.message}")
    }

    /**
     * Write a log entry.
     */
    private fun log(level: Level, message: String) {
        lock.withLock {
            val file = logFile ?: return
            try {
                val timestamp = dateFormat.format(Date())
                val entry = "$timestamp ${level.tag} $message\n"
                file.appendText(entry, Charsets.UTF_8)
            } catch (e: Exception) {
                // Can't log the logging failure - just ignore
                android.util.Log.e("SmashLogger", "Failed to write log: ${e.message}")
            }
        }
    }

    /**
     * Get the last N lines from the log file.
     */
    fun getLastLines(count: Int): List<String> {
        return lock.withLock {
            val file = logFile ?: return@withLock emptyList()
            try {
                if (!file.exists()) return@withLock emptyList()
                
                val lines = file.readLines(Charsets.UTF_8)
                val requestedCount = if (count <= 0) 20 else count
                lines.takeLast(requestedCount)
            } catch (e: Exception) {
                listOf("Error reading log: ${e.message}")
            }
        }
    }

    /**
     * Get the last N lines as a single string.
     */
    fun getLastLinesAsString(count: Int): String {
        return getLastLines(count).joinToString("\n")
    }

    /**
     * Clear the log file.
     */
    fun clear() {
        lock.withLock {
            val file = logFile ?: return
            try {
                file.writeText("", Charsets.UTF_8)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
