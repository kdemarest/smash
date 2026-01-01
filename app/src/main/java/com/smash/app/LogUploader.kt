package com.smash.app

import android.os.Build
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uploads log entries to the remote log endpoint.
 * Batches log lines and uploads periodically to reduce network overhead.
 */
object LogUploader {
    
    private const val BATCH_DELAY_MS = 5000L  // Wait 5 seconds to batch logs
    private const val MAX_BATCH_SIZE = 50     // Max lines per upload
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val pendingLines = ConcurrentLinkedQueue<String>()
    private val uploadJob = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Device identifier - use model name
    private val deviceId: String by lazy {
        val model = Build.MODEL.replace(" ", "-").lowercase()
        "smash-$model"
    }
    
    /**
     * Queue a log line for upload.
     * Lines are batched and uploaded periodically.
     */
    fun queueLine(line: String) {
        val config = SmashApplication.getConfigManager().load()
        if (config.logEndpointUrl.isNullOrBlank()) {
            return  // Log endpoint not configured
        }
        
        pendingLines.add(line)
        scheduleUpload()
    }
    
    /**
     * Schedule an upload if one isn't already pending.
     */
    private fun scheduleUpload() {
        if (uploadJob.compareAndSet(false, true)) {
            scope.launch {
                delay(BATCH_DELAY_MS)
                uploadPendingLogs()
                uploadJob.set(false)
            }
        }
    }
    
    /**
     * Upload all pending log lines.
     */
    private suspend fun uploadPendingLogs() {
        val config = SmashApplication.getConfigManager().load()
        val endpoint = config.logEndpointUrl
        if (endpoint.isNullOrBlank()) {
            pendingLines.clear()
            return
        }
        
        // Drain up to MAX_BATCH_SIZE lines
        val lines = mutableListOf<String>()
        while (lines.size < MAX_BATCH_SIZE) {
            val line = pendingLines.poll() ?: break
            lines.add(line)
        }
        
        if (lines.isEmpty()) return
        
        try {
            val json = JSONObject().apply {
                put("device", deviceId)
                put("lines", JSONArray(lines))
            }
            
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()
            
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Don't log errors to avoid infinite loop
                        android.util.Log.w("LogUploader", "Upload failed: ${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            // Don't log errors to avoid infinite loop
            android.util.Log.w("LogUploader", "Upload error: ${e.message}")
        }
        
        // If there are more pending lines, schedule another upload
        if (pendingLines.isNotEmpty()) {
            scheduleUpload()
        }
    }
    
    /**
     * Force immediate upload of all pending logs.
     * Useful for shutdown or when user requests it.
     */
    fun flushNow() {
        scope.launch {
            uploadPendingLogs()
        }
    }
}
