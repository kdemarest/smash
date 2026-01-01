package com.smash.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Logging utility for smash.
 * Writes timestamped entries to smash.log in app-private storage.
 * 
 * Format: yyyy-MM-dd-HH-mm-ss [level] message (timestamps in UTC)
 */
object SmashLogger {

    private const val LOG_FILENAME = "smash.log"
    private val lock = ReentrantLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val listeners = CopyOnWriteArrayList<OnLogChangedListener>()

    /**
     * Listener for log changes.
     */
    fun interface OnLogChangedListener {
        fun onLogChanged()
    }

    enum class Level(val tag: String) {
        INFO("[info]"),
        WARNING("[warning]"),
        ERROR("[error]"),
        SMS("[SMS]")
    }

    private var logFile: File? = null
    
    // Verbose mode - when true, detailed logs are written; when false, only essential logs
    @Volatile
    var isVerbose: Boolean = false

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
     * Log a verbose message (only written if verbose mode is enabled).
     */
    fun verbose(message: String) {
        if (isVerbose) {
            log(Level.INFO, message)
        } else {
            // Still output to logcat for debugging, but not to file
            android.util.Log.v("SmashLogger", message)
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
        // Also output to Android logcat for debugging
        when (level) {
            Level.INFO, Level.SMS -> android.util.Log.i("SmashLogger", message)
            Level.WARNING -> android.util.Log.w("SmashLogger", message)
            Level.ERROR -> android.util.Log.e("SmashLogger", message)
        }
        
        val entry: String
        lock.withLock {
            val file = logFile ?: return
            try {
                val timestamp = dateFormat.format(Date())
                entry = "$timestamp ${level.tag} $message"
                file.appendText(entry + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                // Can't log the logging failure - just ignore
                android.util.Log.e("SmashLogger", "Failed to write log: ${e.message}")
                return
            }
        }
        
        // Queue for remote upload (outside lock to avoid blocking)
        LogUploader.queueLine(entry)
        
        notifyListeners()
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
        notifyListeners()
    }

    /**
     * Trim the log file to the last N entries.
     */
    fun trim(keepCount: Int) {
        lock.withLock {
            val file = logFile ?: return
            try {
                if (!file.exists()) return
                val lines = file.readLines(Charsets.UTF_8)
                if (lines.size <= keepCount) return
                val trimmed = lines.takeLast(keepCount)
                file.writeText(trimmed.joinToString("\n") + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                // Ignore
            }
        }
        notifyListeners()
    }

    /**
     * Add a listener for log changes.
     */
    fun addOnLogChangedListener(listener: OnLogChangedListener) {
        listeners.add(listener)
    }

    /**
     * Remove a listener for log changes.
     */
    fun removeOnLogChangedListener(listener: OnLogChangedListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.onLogChanged()
        }
    }
}
