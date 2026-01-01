package com.smash.app

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Unified processor for all incoming messages (SMS and MMS).
 * 
 * Provides a single queue and single consumer thread for sequential,
 * uniform processing regardless of message source.
 */
class MessageProcessor(
    private val onMessage: (IncomingMessage) -> Unit
) {
    private val queue = LinkedBlockingQueue<IncomingMessage>()
    private val isRunning = AtomicBoolean(false)
    private var processingThread: Thread? = null

    /**
     * Start the processor.
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            processingThread = thread(name = "MessageProcessor") {
                processLoop()
            }
            SmashLogger.verbose("MessageProcessor started")
        }
    }

    /**
     * Stop the processor.
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            processingThread?.interrupt()
            processingThread = null
            SmashLogger.verbose("MessageProcessor stopped")
        }
    }

    /**
     * Queue a message for processing.
     * Can be called from any thread.
     */
    fun enqueue(message: IncomingMessage) {
        queue.offer(message)
    }

    /**
     * Main processing loop.
     */
    private fun processLoop() {
        while (isRunning.get()) {
            try {
                // Block waiting for next message (with timeout to check isRunning)
                val message = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                if (message != null) {
                    try {
                        onMessage(message)
                    } catch (e: Exception) {
                        SmashLogger.error("Error processing message from ${message.sender}", e)
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
