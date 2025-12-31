package com.smash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps smash running.
 * Handles SMS and MMS processing coordination.
 */
class SmashService : Service() {

    private lateinit var messageProcessor: MessageProcessor
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var messageForwarder: MessageForwarder
    private lateinit var mmsObserver: MmsObserver

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "smash_service"

        @Volatile
        private var instance: SmashService? = null

        /**
         * Get the running service instance.
         */
        fun getInstance(): SmashService? = instance

        /**
         * Start the foreground service.
         */
        fun start(context: Context) {
            val intent = Intent(context, SmashService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, SmashService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        SmashLogger.info("SmashService onCreate")
        createNotificationChannel()
        
        // Initialize command processor
        commandProcessor = CommandProcessor(this, SmashApplication.getConfigManager())
        
        // Initialize message forwarder
        messageForwarder = MessageForwarder(this)
        
        // Initialize unified message processor for both SMS and MMS
        messageProcessor = MessageProcessor { message ->
            handleIncomingMessage(message)
        }
        messageProcessor.start()
        
        // Initialize MMS observer - enqueues to shared processor
        mmsObserver = MmsObserver(this) { message ->
            messageProcessor.enqueue(message)
        }
        mmsObserver.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SmashLogger.info("SmashService onStartCommand")
        
        // Start foreground immediately with "checking" status to avoid ANR
        val initialNotification = createNotification(checking = true)
        startForeground(NOTIFICATION_ID, initialNotification)
        
        // Now update with actual status after checking
        updateNotification()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        messageProcessor.stop()
        mmsObserver.unregister()
        SmashLogger.info("SmashService onDestroy")
    }

    /**
     * Explicitly trigger an MMS check.
     * Called by MmsDownloadReceiver as a fallback when ContentObserver doesn't fire.
     */
    fun triggerMmsCheck() {
        mmsObserver.checkNow()
    }

    /**
     * Enqueue a message for processing.
     * Called by SmsReceiver and MmsObserver.
     */
    fun enqueueMessage(message: IncomingMessage) {
        messageProcessor.enqueue(message)
    }

    /**
     * Handle an incoming message (SMS or MMS).
     * Determines if it's a command or a forwardable message.
     * Runs on MessageProcessor thread.
     */
    private fun handleIncomingMessage(message: IncomingMessage) {
        val config = SmashApplication.getConfigManager().load()
        val prefix = config.prefix
        val body = message.body.trim()

        // Check if this is a command (commands don't have attachments typically)
        if (CommandParser.isCommand(body, prefix)) {
            val result = commandProcessor.process(message.sender, body)
            SmsUtils.sendReply(this, message.sender, result.reply)
        } else {
            // Log the incoming message
            val attachmentInfo = if (message.hasAttachments) {
                " (${message.attachments.size} attachments)"
            } else ""
            SmashLogger.sms("from ${message.sender}: ${message.body}$attachmentInfo")
            
            // Forward to targets
            val targetCount = config.targets.size
            if (targetCount > 0) {
                val result = messageForwarder.forward(
                    message = message,
                    config = config
                )
                
                if (result.allSuccessful) {
                    SmashLogger.info("repeated to ${result.totalTargets} targets")
                } else {
                    SmashLogger.info("repeated to ${result.totalTargets} targets (${result.successCount} ok, ${result.failureCount} failed)")
                }
            } else {
                SmashLogger.info("no targets configured, message not forwarded")
            }
        }
    }

    /**
     * Create the notification channel (required for Android O+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // No sound, no vibration
            ).apply {
                description = "Keeps smash running to forward SMS messages"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create the foreground notification.
     * @param checking If true, show "checking" status instead of actual status
     */
    private fun createNotification(checking: Boolean = false): Notification {
        // Intent to open StatusActivity when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, StatusActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (checking) {
            getString(R.string.notification_text_checking)
        } else {
            // Check if we're the default SMS app
            if (PhoneUtils.isDefaultSmsApp(this)) {
                getString(R.string.notification_text)
            } else {
                getString(R.string.notification_text_warning)
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Update the notification (e.g., when default SMS app status changes).
     */
    fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
}
