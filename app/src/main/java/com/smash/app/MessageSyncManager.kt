package com.smash.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony

/**
 * Periodically syncs with the system SMS/MMS database to catch any messages
 * that may have been missed due to app crashes, restarts, or other issues.
 * 
 * This is a safety net to ensure message reliability. When Smash is the default
 * SMS app, it receives broadcasts for incoming messages, but if those broadcasts
 * are missed (e.g., app killed), this manager will catch up by scanning the
 * system database.
 */
class MessageSyncManager(
    private val context: Context,
    private val onMessageFound: (IncomingMessage) -> Unit
) {
    companion object {
        private const val TAG = "MessageSyncManager"
        private const val PREFS_NAME = "message_sync"
        private const val PREF_LAST_SMS_ID = "last_sms_id"
        private const val PREF_LAST_MMS_ID = "last_mms_id"
        private const val PREF_LAST_SYNC_TIME = "last_sync_time"
        
        // Default sync interval: 5 minutes
        private const val DEFAULT_SYNC_INTERVAL_MS = 5 * 60 * 1000L
        
        // How far back to look on first run (24 hours)
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60 * 1000L
        
        // Maximum messages to process per sync (to avoid overwhelming)
        private const val MAX_MESSAGES_PER_SYNC = 50
        
        private val SMS_INBOX_URI: Uri = Telephony.Sms.Inbox.CONTENT_URI
        private val MMS_INBOX_URI: Uri = Uri.parse("content://mms/inbox")
    }

    private val handlerThread = HandlerThread("MessageSyncThread")
    private var handler: Handler? = null
    private var isRunning = false
    private var syncIntervalMs = DEFAULT_SYNC_INTERVAL_MS

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Stats for the last sync
    data class SyncResult(
        val smsFound: Int,
        val mmsFound: Int,
        val smsProcessed: Int,
        val mmsProcessed: Int,
        val durationMs: Long
    ) {
        val totalFound get() = smsFound + mmsFound
        val totalProcessed get() = smsProcessed + mmsProcessed
        val hadMissedMessages get() = totalProcessed > 0
    }

    /**
     * Start periodic sync checks.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        
        // Initialize watermarks if this is first run
        initializeWatermarks()
        
        // Do an initial sync immediately to catch anything missed while stopped
        handler?.post { performSync() }
        
        // Schedule periodic syncs
        scheduleNextSync()
        
        SmashLogger.verbose("$TAG: started with ${syncIntervalMs/1000}s interval")
    }

    /**
     * Stop periodic sync checks.
     */
    fun stop() {
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        SmashLogger.verbose("$TAG: stopped")
    }

    /**
     * Manually trigger a sync now. Returns the result.
     */
    fun syncNow(): SyncResult {
        return performSync()
    }

    /**
     * Initialize watermarks on first run.
     * Sets them to current highest IDs so we don't process old messages.
     */
    private fun initializeWatermarks() {
        val lastSmsId = prefs.getLong(PREF_LAST_SMS_ID, -1)
        val lastMmsId = prefs.getLong(PREF_LAST_MMS_ID, -1)
        
        if (lastSmsId == -1L || lastMmsId == -1L) {
            // First run - set watermarks to current highest
            val currentSmsId = getHighestSmsId()
            val currentMmsId = getHighestMmsId()
            
            prefs.edit()
                .putLong(PREF_LAST_SMS_ID, currentSmsId)
                .putLong(PREF_LAST_MMS_ID, currentMmsId)
                .putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
                .apply()
            
            SmashLogger.verbose("$TAG: initialized watermarks SMS=$currentSmsId MMS=$currentMmsId")
        }
    }

    private fun scheduleNextSync() {
        handler?.postDelayed({
            if (isRunning) {
                performSync()
                scheduleNextSync()
            }
        }, syncIntervalMs)
    }

    /**
     * Perform the actual sync - check for missed SMS and MMS.
     */
    private fun performSync(): SyncResult {
        val startTime = System.currentTimeMillis()
        
        var smsFound = 0
        var mmsFound = 0
        var smsProcessed = 0
        var mmsProcessed = 0
        
        try {
            // Sync SMS
            val smsResult = syncSms()
            smsFound = smsResult.first
            smsProcessed = smsResult.second
            
            // Sync MMS
            val mmsResult = syncMms()
            mmsFound = mmsResult.first
            mmsProcessed = mmsResult.second
            
            // Update last sync time
            prefs.edit()
                .putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
                .apply()
            
            if (smsProcessed > 0 || mmsProcessed > 0) {
                SmashLogger.warning("$TAG: recovered $smsProcessed SMS and $mmsProcessed MMS messages!")
            } else {
                SmashLogger.verbose("$TAG: sync complete, no missed messages")
            }
            
        } catch (e: Exception) {
            SmashLogger.error("$TAG: sync failed", e)
        }
        
        return SyncResult(
            smsFound = smsFound,
            mmsFound = mmsFound,
            smsProcessed = smsProcessed,
            mmsProcessed = mmsProcessed,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Sync SMS messages. Returns (found, processed) counts.
     */
    private fun syncSms(): Pair<Int, Int> {
        val lastSmsId = prefs.getLong(PREF_LAST_SMS_ID, 0)
        var highestSeenId = lastSmsId
        var found = 0
        var processed = 0
        
        val cursor = context.contentResolver.query(
            SMS_INBOX_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            "${Telephony.Sms._ID} > ?",
            arrayOf(lastSmsId.toString()),
            "${Telephony.Sms._ID} ASC LIMIT $MAX_MESSAGES_PER_SYNC"
        )
        
        cursor?.use {
            found = it.count
            
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: continue
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val read = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ))
                
                // Only process unread messages that arrived recently (within lookback window)
                // This helps avoid reprocessing very old messages on first sync
                val age = System.currentTimeMillis() - date
                if (read == 0 && age < INITIAL_LOOKBACK_MS) {
                    SmashLogger.warning("$TAG: found missed SMS from $address (id=$id, age=${age/1000}s)")
                    
                    val message = IncomingMessage(
                        sender = address,
                        body = body,
                        timestamp = date,
                        attachments = emptyList()
                    )
                    
                    onMessageFound(message)
                    processed++
                    
                    // Only update watermark for messages we actually processed
                    highestSeenId = maxOf(highestSeenId, id)
                } else {
                    SmashLogger.verbose("$TAG: skipping SMS id=$id read=$read age=${age/1000}s")
                }
            }
        }
        
        // Update watermark
        if (highestSeenId > lastSmsId) {
            prefs.edit().putLong(PREF_LAST_SMS_ID, highestSeenId).apply()
        }
        
        return Pair(found, processed)
    }

    /**
     * Sync MMS messages. Returns (found, processed) counts.
     */
    private fun syncMms(): Pair<Int, Int> {
        val lastMmsId = prefs.getLong(PREF_LAST_MMS_ID, 0)
        var highestSeenId = lastMmsId
        var found = 0
        var processed = 0
        
        val cursor = context.contentResolver.query(
            MMS_INBOX_URI,
            arrayOf("_id", "date", "read"),
            "_id > ?",
            arrayOf(lastMmsId.toString()),
            "_id ASC"
        )
        
        cursor?.use {
            found = it.count
            
            // Limit processing
            var count = 0
            while (it.moveToNext() && count < MAX_MESSAGES_PER_SYNC) {
                val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                val date = it.getLong(it.getColumnIndexOrThrow("date")) * 1000 // MMS date is in seconds
                val read = it.getInt(it.getColumnIndexOrThrow("read"))
                
                highestSeenId = maxOf(highestSeenId, id)
                
                // Only process unread messages within lookback window
                val age = System.currentTimeMillis() - date
                if (read == 0 && age < INITIAL_LOOKBACK_MS) {
                    val message = extractMmsMessage(id, date)
                    if (message != null) {
                        SmashLogger.warning("$TAG: found missed MMS from ${message.sender} (id=$id, age=${age/1000}s)")
                        onMessageFound(message)
                        processed++
                    }
                }
                
                count++
            }
        }
        
        // Update watermark
        if (highestSeenId > lastMmsId) {
            prefs.edit().putLong(PREF_LAST_MMS_ID, highestSeenId).apply()
        }
        
        return Pair(found, processed)
    }

    /**
     * Extract an MMS message from the content provider.
     */
    private fun extractMmsMessage(mmsId: Long, timestamp: Long): IncomingMessage? {
        // Get sender address
        val sender = getMmsSender(mmsId) ?: return null
        
        // Get body and attachments
        var textBody = ""
        val attachments = mutableListOf<MediaAttachment>()
        
        val partUri = Uri.parse("content://mms/part")
        val partCursor = context.contentResolver.query(
            partUri,
            arrayOf("_id", "ct", "text", "_data"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )
        
        partCursor?.use { cursor ->
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                val contentType = cursor.getString(cursor.getColumnIndexOrThrow("ct")) ?: continue
                
                when {
                    contentType == "text/plain" -> {
                        textBody = cursor.getString(cursor.getColumnIndexOrThrow("text")) ?: ""
                    }
                    contentType.startsWith("image/") -> {
                        val imageData = readMmsPartData(partId)
                        if (imageData != null) {
                            attachments.add(MediaAttachment(
                                uri = Uri.EMPTY,
                                mimeType = contentType,
                                data = imageData
                            ))
                        }
                    }
                }
            }
        }
        
        return IncomingMessage(
            sender = sender,
            body = textBody,
            timestamp = timestamp,
            attachments = attachments
        )
    }

    /**
     * Get the sender address for an MMS message.
     */
    private fun getMmsSender(mmsId: Long): String? {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = context.contentResolver.query(
            addrUri,
            arrayOf("address", "type"),
            "type = 137",  // PduHeaders.FROM
            null,
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val address = it.getString(it.getColumnIndexOrThrow("address"))
                if (!address.isNullOrBlank() && 
                    address != "insert-address-token" &&
                    !address.contains("insert-address", ignoreCase = true)) {
                    return address.substringBefore("/")
                }
            }
        }
        
        return null
    }

    /**
     * Read the binary data for an MMS part.
     */
    private fun readMmsPartData(partId: Long): ByteArray? {
        val partUri = Uri.parse("content://mms/part/$partId")
        return try {
            context.contentResolver.openInputStream(partUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            SmashLogger.warning("$TAG: failed to read MMS part $partId: ${e.message}")
            null
        }
    }

    /**
     * Get the highest SMS ID currently in the database.
     */
    private fun getHighestSmsId(): Long {
        val cursor = context.contentResolver.query(
            SMS_INBOX_URI,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            "${Telephony.Sms._ID} DESC LIMIT 1"
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
            } else 0L
        } ?: 0L
    }

    /**
     * Get the highest MMS ID currently in the database.
     */
    private fun getHighestMmsId(): Long {
        val cursor = context.contentResolver.query(
            MMS_INBOX_URI,
            arrayOf("_id"),
            null,
            null,
            "_id DESC LIMIT 1"
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow("_id"))
            } else 0L
        } ?: 0L
    }

    /**
     * Get the last sync time.
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(PREF_LAST_SYNC_TIME, 0)
    }

    /**
     * Get current watermark info for status display.
     */
    fun getStatus(): String {
        val lastSmsId = prefs.getLong(PREF_LAST_SMS_ID, 0)
        val lastMmsId = prefs.getLong(PREF_LAST_MMS_ID, 0)
        val lastSync = prefs.getLong(PREF_LAST_SYNC_TIME, 0)
        
        val ago = if (lastSync > 0) {
            val seconds = (System.currentTimeMillis() - lastSync) / 1000
            when {
                seconds < 60 -> "${seconds}s ago"
                seconds < 3600 -> "${seconds / 60}m ago"
                else -> "${seconds / 3600}h ago"
            }
        } else "never"
        
        return "SMS=$lastSmsId MMS=$lastMmsId sync=$ago"
    }

    /**
     * Reset watermarks to current highest IDs.
     * Useful if you want to skip catching up on old messages.
     */
    fun resetWatermarks() {
        val currentSmsId = getHighestSmsId()
        val currentMmsId = getHighestMmsId()
        
        prefs.edit()
            .putLong(PREF_LAST_SMS_ID, currentSmsId)
            .putLong(PREF_LAST_MMS_ID, currentMmsId)
            .putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
            .apply()
        
        SmashLogger.info("$TAG: reset watermarks SMS=$currentSmsId MMS=$currentMmsId")
    }
}
