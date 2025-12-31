package com.smash.app

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread

/**
 * Observes the MMS content provider for new incoming messages.
 * 
 * Runs on a dedicated background looper so that database queries
 * in onChange() don't block the main thread. When a new MMS is
 * detected, it extracts the content and enqueues an IncomingMessage
 * to the shared MessageProcessor.
 */
class MmsObserver(
    private val context: Context,
    private val onMmsReceived: (IncomingMessage) -> Unit
) {
    companion object {
        private val MMS_URI = Uri.parse("content://mms")
        private val MMS_INBOX_URI = Uri.parse("content://mms/inbox")
    }

    // Background thread for ContentObserver callbacks
    private val handlerThread = HandlerThread("MmsObserverThread")
    private var observer: ContentObserver? = null

    // Track the highest MMS ID we've seen to detect new ones
    private var lastKnownMmsId: Long = 0
    
    // Track processed IDs to avoid duplicates (belt and suspenders)
    private val processedIds = mutableSetOf<Long>()

    /**
     * Register this observer with the content resolver.
     * Call this when the service starts.
     */
    fun register() {
        // Start background thread
        handlerThread.start()
        val handler = Handler(handlerThread.looper)
        
        // Get the current highest MMS ID before we start observing
        lastKnownMmsId = getHighestMmsId()
        
        // Create observer that runs on background thread
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                // Already on background thread - safe to query DB
                checkForNewMms()
            }
        }
        
        // Register for changes to the MMS content provider
        context.contentResolver.registerContentObserver(
            MMS_URI,
            true,  // notifyForDescendants
            observer!!
        )
        
        SmashLogger.info("MmsObserver registered (last known ID: $lastKnownMmsId)")
    }

    /**
     * Unregister this observer.
     * Call this when the service stops.
     */
    fun unregister() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        handlerThread.quitSafely()
        SmashLogger.info("MmsObserver unregistered")
    }

    /**
     * Explicitly trigger an MMS check with retry.
     * Called after MMS download completes. Polls until new MMS appears or timeout.
     * Note: This is now a backup - primary path is direct PDU parsing in MmsDownloadReceiver.
     */
    fun checkNow() {
        val handler = Handler(handlerThread.looper)
        handler.post { checkWithRetry(attemptsRemaining = 10, delayMs = 500) }
    }

    /**
     * Check for new MMS with retry logic.
     * Polls the database until we find a new MMS or give up.
     */
    private fun checkWithRetry(attemptsRemaining: Int, delayMs: Long) {
        val beforeId = lastKnownMmsId
        checkForNewMms()
        
        // If we found something new, we're done
        if (lastKnownMmsId > beforeId) {
            SmashLogger.info("MmsObserver: found new MMS after ${11 - attemptsRemaining} attempts")
            return
        }
        
        // If we have retries left, schedule another check
        if (attemptsRemaining > 1) {
            Handler(handlerThread.looper).postDelayed({
                checkWithRetry(attemptsRemaining - 1, delayMs)
            }, delayMs)
        } else {
            SmashLogger.info("MmsObserver: no new MMS in database (may have been handled via direct PDU parsing)")
        }
    }

    /**
     * Check for any new MMS messages since we last looked.
     * Runs on background looper thread.
     */
    private fun checkForNewMms() {
        val contentResolver = context.contentResolver
        
        SmashLogger.info("MmsObserver: checking for new MMS (lastKnownId=$lastKnownMmsId)")
        
        // Query for MMS in inbox with ID greater than last known
        val cursor = contentResolver.query(
            MMS_INBOX_URI,
            arrayOf("_id", "date", "msg_box"),
            "_id > ?",
            arrayOf(lastKnownMmsId.toString()),
            "_id ASC"  // Process in order received
        )
        
        if (cursor == null) {
            SmashLogger.warning("MmsObserver: cursor is null - content provider issue?")
            return
        }
        
        SmashLogger.info("MmsObserver: found ${cursor.count} new MMS candidates")
        
        cursor.use {
            while (it.moveToNext()) {
                val mmsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                
                // Update our high water mark
                if (mmsId > lastKnownMmsId) {
                    lastKnownMmsId = mmsId
                }
                
                // Skip if already processed
                val alreadyProcessed = synchronized(processedIds) {
                    if (mmsId in processedIds) {
                        true
                    } else {
                        processedIds.add(mmsId)

                        // Trim old IDs
                        if (processedIds.size > 100) {
                            val oldest = processedIds.minOrNull()
                            if (oldest != null) processedIds.remove(oldest)
                        }
                        false
                    }
                }

                if (alreadyProcessed) {
                    SmashLogger.info("MmsObserver: skipping already-processed MMS id=$mmsId")
                    continue
                }
                
                SmashLogger.info("MmsObserver: extracting MMS id=$mmsId")
                
                // Extract the MMS
                val message = extractMms(contentResolver, mmsId)
                if (message != null) {
                    SmashLogger.info("MmsObserver: new MMS id=$mmsId from ${message.sender} (${message.attachments.size} attachments)")
                    // Enqueue to shared processor
                    onMmsReceived(message)
                } else {
                    SmashLogger.warning("MmsObserver: could not extract MMS id=$mmsId")
                }
            }
        }
    }

    /**
     * Get the highest MMS ID currently in the inbox.
     */
    private fun getHighestMmsId(): Long {
        val cursor = context.contentResolver.query(
            MMS_INBOX_URI,
            arrayOf("_id"),
            null,
            null,
            "_id DESC"
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        
        return 0
    }

    /**
     * Extract a complete MMS message from the content provider.
     */
    private fun extractMms(contentResolver: ContentResolver, mmsId: Long): IncomingMessage? {
        // Get MMS metadata
        val mmsCursor = contentResolver.query(
            Uri.parse("content://mms/$mmsId"),
            arrayOf("_id", "date"),
            null,
            null,
            null
        )
        
        val timestamp = mmsCursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow("date")) * 1000  // MMS date is in seconds
            } else null
        } ?: System.currentTimeMillis()
        
        // Get sender
        val sender = extractMmsSender(contentResolver, mmsId)
        if (sender == null) {
            SmashLogger.warning("MmsObserver: no valid sender found for MMS id=$mmsId (may be placeholder)")
            return null
        }
        SmashLogger.info("MmsObserver: MMS id=$mmsId sender=$sender")
        
        // Get body and attachments
        val (body, attachments) = extractMmsParts(contentResolver, mmsId)
        SmashLogger.info("MmsObserver: MMS id=$mmsId body='${body.take(50)}${if (body.length > 50) "..." else ""}' attachments=${attachments.size}")
        
        return IncomingMessage(
            sender = sender,
            body = body,
            timestamp = timestamp,
            attachments = attachments
        )
    }

    /**
     * Extract the sender address from an MMS message.
     * Filters out placeholder values that some carriers/devices use.
     */
    private fun extractMmsSender(contentResolver: ContentResolver, mmsId: Long): String? {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = contentResolver.query(
            addrUri,
            arrayOf("address", "type"),
            "type = 137",  // 137 = PduHeaders.FROM
            null,
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val address = it.getString(it.getColumnIndexOrThrow("address"))
                
                // Filter out garbage placeholder values
                if (address.isNullOrBlank() || 
                    address == "insert-address-token" ||
                    address.contains("insert-address", ignoreCase = true)) {
                    return null
                }
                
                return address
            }
        }
        
        return null
    }

    /**
     * Extract text body and media attachments from MMS parts.
     */
    private fun extractMmsParts(contentResolver: ContentResolver, mmsId: Long): Pair<String, List<MediaAttachment>> {
        val partUri = Uri.parse("content://mms/part")
        
        val cursor = contentResolver.query(
            partUri,
            arrayOf("_id", "ct", "_data", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )
        
        var textBody = ""
        val attachments = mutableListOf<MediaAttachment>()
        
        cursor?.use {
            while (it.moveToNext()) {
                val partId = it.getLong(it.getColumnIndexOrThrow("_id"))
                val contentType = it.getString(it.getColumnIndexOrThrow("ct")) ?: continue
                
                when {
                    contentType == "text/plain" -> {
                        val text = it.getString(it.getColumnIndexOrThrow("text"))
                        if (!text.isNullOrEmpty()) {
                            textBody = text
                        } else {
                            textBody = readTextPart(contentResolver, partId)
                        }
                    }
                    contentType.startsWith("image/") -> {
                        val partContentUri = Uri.parse("content://mms/part/$partId")
                        attachments.add(MediaAttachment(
                            uri = partContentUri,
                            mimeType = contentType
                        ))
                    }
                }
            }
        }
        
        return Pair(textBody, attachments)
    }

    /**
     * Read text content from an MMS part.
     */
    private fun readTextPart(contentResolver: ContentResolver, partId: Long): String {
        val partUri = Uri.parse("content://mms/part/$partId")
        return try {
            contentResolver.openInputStream(partUri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            SmashLogger.error("Failed to read MMS text part", e)
            ""
        }
    }
}
