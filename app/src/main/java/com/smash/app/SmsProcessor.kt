package com.smash.app

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Data class representing an incoming SMS message.
 */
data class IncomingSms(
    val sender: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Processor for incoming SMS messages.
 * Ensures sequential processing of multiple simultaneous messages.
 */
class SmsProcessor(
    private val onMessage: (IncomingSms) -> Unit
) {
    private val queue = LinkedBlockingQueue<IncomingSms>()
    private val isRunning = AtomicBoolean(false)
    private var processingThread: Thread? = null

    /**
     * Start the processor.
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            processingThread = thread(name = "SmsProcessor") {
                processLoop()
            }
            SmashLogger.info("SmsProcessor started")
        }
    }

    /**
     * Stop the processor.
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            processingThread?.interrupt()
            processingThread = null
            SmashLogger.info("SmsProcessor stopped")
        }
    }

    /**
     * Queue an SMS for processing.
     */
    fun enqueue(sms: IncomingSms) {
        queue.offer(sms)
    }

    /**
     * Main processing loop.
     */
    private fun processLoop() {
        while (isRunning.get()) {
            try {
                // Block waiting for next SMS (with timeout to check isRunning)
                val sms = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                if (sms != null) {
                    try {
                        //SmashLogger.info("Processing SMS from ${sms.sender}")
                        onMessage(sms)
                        //SmashLogger.info("Finished processing SMS from ${sms.sender}")
                    } catch (e: Exception) {
                        SmashLogger.error("Error processing SMS from ${sms.sender}", e)
                    }
                }
            } catch (e: InterruptedException) {
                // Expected when stopping
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
