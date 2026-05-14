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
import android.text.Html
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
    private lateinit var messageSyncManager: MessageSyncManager
    private lateinit var powerMonitor: PowerMonitor
    private lateinit var signalMonitor: SignalMonitor
    private lateinit var storageMonitor: StorageMonitor
    private lateinit var tailscaleMonitor: TailscaleMonitor
    private lateinit var endpointMonitor: EndpointMonitor
    private var wasDefaultSmsApp = true

    @Volatile
    private var replyPhoneNum: String? = null

    fun getReplyPhoneNum(): String? = replyPhoneNum

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "smash_service"
        const val EXTRA_TRIGGER = "trigger"
        const val TRIGGER_BOOT = "boot"
        const val TRIGGER_USER = "user"

        @Volatile
        private var instance: SmashService? = null

        /**
         * Get the running service instance.
         */
        fun getInstance(): SmashService? = instance

        /**
         * Start the foreground service.
         * @param trigger How the service was started: TRIGGER_BOOT or TRIGGER_USER
         */
        fun start(context: Context, trigger: String = TRIGGER_USER) {
            val intent = Intent(context, SmashService::class.java).apply {
                putExtra(EXTRA_TRIGGER, trigger)
            }
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
            SmashLogger.info("SmashService stop requested")
            instance?.stopSelf()
            val intent = Intent(context, SmashService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        SmashLogger.verbose("SmashService onCreate")
        createNotificationChannel()
        
        // Initialize centralized beep service
        BeepService.init()
        
        // Initialize centralized alert manager
        AlertManager.init(this)
        
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
        
        // Initialize message sync manager - catches missed messages
        messageSyncManager = MessageSyncManager(this) { message ->
            messageProcessor.enqueue(message)
        }
        messageSyncManager.start()

        // Initialize power monitor - beeps when unplugged
        powerMonitor = PowerMonitor(this) { isPluggedIn ->
            updateNotification()
            notifyTargetsOfPowerState(isPluggedIn)
        }
        powerMonitor.start()

        // Initialize signal monitor - beeps when cell signal lost
        signalMonitor = SignalMonitor(this) { hasSignal, reason ->
            updateNotification()
            notifyTargetsOfSignalState(hasSignal, reason)
        }
        signalMonitor.start()

        // Initialize storage monitor - alerts when storage low
        storageMonitor = StorageMonitor(this) { isLow ->
            updateNotification()
            notifyTargetsOfStorageState(isLow)
        }
        storageMonitor.start()

        // Initialize Tailscale/VPN monitor - alerts when VPN goes down
        tailscaleMonitor = TailscaleMonitor(this) { isUp ->
            updateNotification()
            notifyTargetsOfVpnState(isUp)
        }
        tailscaleMonitor.start()

        // Initialize endpoint monitor - checks mail/log endpoints hourly
        endpointMonitor = EndpointMonitor(this) { isUp ->
            updateNotification()
            notifyTargetsOfEndpointState(isUp)
        }
        endpointMonitor.start()

        // Track default SMS app status
        wasDefaultSmsApp = PhoneUtils.isDefaultSmsApp(this)
    }

    fun ensureWarningsTarget() {
        val config = SmashApplication.getConfigManager().load()
        if (config.targets.isEmpty()) {
            SmashLogger.info("ensureWarningsTarget: no targets configured, skipping")
            return
        }
        val existing = config.targetsWithFlag("getWarnings")
        if (existing.isNotEmpty()) {
            SmashLogger.info("ensureWarningsTarget: getWarnings already set on ${existing.joinToString()}, no change")
            return
        }
        val first = config.targets.first()
        val (newConfig, wasAdded) = config.addFlag(first, "getWarnings")
        if (wasAdded) {
            val saved = SmashApplication.getConfigManager().save(newConfig)
            SmashLogger.info("ensureWarningsTarget: auto-assigned getWarnings to $first (saved=$saved)")
        } else {
            SmashLogger.info("ensureWarningsTarget: addFlag returned wasAdded=false for $first (unexpected)")
        }
    }

    /**
     * Send SMS to all targets when power state changes.
     */
    private fun notifyTargetsOfPowerState(isPluggedIn: Boolean) {
        val config = SmashApplication.getConfigManager().load()
        val message = if (isPluggedIn) "Smash phone plugged back in" else "Smash phone unplugged"

        for (target in config.targetsWithFlag("getWarnings")) {
            if (!target.contains('@')) {
                val cleanedNumber = PhoneUtils.cleanPhone(target)
                if (cleanedNumber.isNotEmpty()) {
                    SmsUtils.sendSms(this, cleanedNumber, message)
                }
            }
        }
    }

    /**
     * Notify targets when signal state changes.
     * Signal lost: email only (can't SMS without signal, but WiFi may work)
     * Signal restored: SMS to phone targets
     */
    private fun notifyTargetsOfSignalState(hasSignal: Boolean, reason: String?) {
        val config = SmashApplication.getConfigManager().load()
        val warningTargets = config.targetsWithFlag("getWarnings")

        if (hasSignal) {
            val message = "✅ Smash phone signal restored"
            for (target in warningTargets) {
                if (!target.contains('@')) {
                    val cleanedNumber = PhoneUtils.cleanPhone(target)
                    if (cleanedNumber.isNotEmpty()) {
                        SmsUtils.sendSms(this, cleanedNumber, message)
                    }
                }
            }
        } else {
            val message = "📵 Smash phone lost cell signal!\n\nReason: ${reason ?: "Unknown"}\n\nSMS forwarding is NOT working until this is resolved."
            val emailTargets = warningTargets.filter { it.contains('@') }
            val recipients = emailTargets.ifEmpty {
                // No getWarnings email target — fall back to first email in targets list
                listOfNotNull(config.targets.firstOrNull { it.contains('@') })
                    .also { if (it.isNotEmpty()) SmashLogger.info("signal loss: no getWarnings email target, falling back to ${it.first()}") }
            }
            if (recipients.isEmpty()) {
                SmashLogger.error("signal loss: no email target available — cannot send signal loss alert!")
            }
            for (target in recipients) {
                messageForwarder.forwardToEmail(
                    origin = "smash-alert",
                    body = message,
                    destination = target,
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * Notify targets when storage state changes.
     * Storage low: email only (SMS still works, but want to alert)
     */
    private fun notifyTargetsOfStorageState(isLow: Boolean) {
        val config = SmashApplication.getConfigManager().load()

        if (isLow) {
            val message = "💾 Smash phone storage is low!\n\nPlease clear some space to ensure MMS downloads and logs work properly."
            for (target in config.targetsWithFlag("getWarnings")) {
                if (target.contains('@')) {
                    messageForwarder.forwardToEmail(
                        origin = "smash-alert",
                        body = message,
                        destination = target,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
        }
        // No notification when storage is restored - not important
    }

    private fun notifyTargetsOfEndpointState(isUp: Boolean) {
        val config = SmashApplication.getConfigManager().load()
        val message = if (isUp) "Smash endpoints restored" else "Smash endpoint unreachable!"

        for (target in config.targetsWithFlag("getWarnings")) {
            if (!target.contains('@')) {
                val cleanedNumber = PhoneUtils.cleanPhone(target)
                if (cleanedNumber.isNotEmpty()) {
                    SmsUtils.sendSms(this, cleanedNumber, message)
                }
            }
        }
    }

    /**
     * Notify targets when VPN state changes via SMS.
     * VPN loss doesn't affect WiFi/cellular, so SMS works fine in both directions.
     */
    private fun notifyTargetsOfVpnState(isUp: Boolean) {
        val config = SmashApplication.getConfigManager().load()
        val message = if (isUp) "Smash phone VPN restored" else "Smash phone VPN went down!"

        for (target in config.targetsWithFlag("getWarnings")) {
            if (!target.contains('@')) {
                val cleanedNumber = PhoneUtils.cleanPhone(target)
                if (cleanedNumber.isNotEmpty()) {
                    SmsUtils.sendSms(this, cleanedNumber, message)
                }
            }
        }
    }

    /**
     * Check if we lost default SMS app status and notify via email (no loud alert).
     */
    private fun checkDefaultSmsAppStatus() {
        val isDefaultNow = PhoneUtils.isDefaultSmsApp(this)
        
        if (wasDefaultSmsApp && !isDefaultNow) {
            SmashLogger.error("Lost default SMS app status!")
            val config = SmashApplication.getConfigManager().load()
            val message = "⚠️ Smash lost default SMS app status!\n\nSMS forwarding will NOT work until this is fixed.\n\nOpen the app and grant default SMS permissions."
            for (target in config.targets) {
                if (target.contains('@')) {
                    messageForwarder.forwardToEmail(
                        origin = "smash-alert",
                        body = message,
                        destination = target,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
        } else if (!wasDefaultSmsApp && isDefaultNow) {
            SmashLogger.info("Default SMS app status restored")
        }
        
        wasDefaultSmsApp = isDefaultNow
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER) ?: "unknown"
        SmashLogger.info("SmashService started (trigger: $trigger)")
        ensureWarningsTarget()
        
        // Start foreground immediately with "checking" status to avoid ANR
        val initialNotification = createNotification(checking = true)
        startForeground(NOTIFICATION_ID, initialNotification)
        
        // Now update with actual status after checking
        updateNotification()

        // Drain any messages that arrived before service was ready
        val pendingMessages = SmashApplication.drainPendingMessages()
        for (msg in pendingMessages) {
            messageProcessor.enqueue(msg)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        messageProcessor.stop()
        mmsObserver.unregister()
        messageSyncManager.stop()
        powerMonitor.stop()
        signalMonitor.stop()
        storageMonitor.stop()
        tailscaleMonitor.stop()
        endpointMonitor.stop()
        BeepService.release()
        SmashLogger.verbose("SmashService onDestroy")
    }

    /**
     * Explicitly trigger an MMS check.
     * Called by MmsDownloadReceiver as a fallback when ContentObserver doesn't fire.
     */
    fun triggerMmsCheck() {
        mmsObserver.checkNow()
    }

    /**
     * Get the message sync manager for status/manual sync.
     */
    fun getMessageSyncManager(): MessageSyncManager = messageSyncManager

    /**
     * Check if power is currently plugged in.
     */
    fun isPowerPluggedIn(): Boolean = if (::powerMonitor.isInitialized) powerMonitor.isPluggedIn else true

    /**
     * Check if cell signal is usable.
     */
    fun hasUsableSignal(): Boolean = if (::signalMonitor.isInitialized) signalMonitor.hasUsableSignal else true

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

        // Check if this is a command
        if (CommandParser.isCommand(body, prefix)) {
            val result = commandProcessor.process(message.sender, body)
            SmsUtils.sendReply(this, message.sender, result.reply)
        } else {
            // Check if sender is blocked
            if (BlockedNumbersHelper.isBlocked(this, message.sender)) {
                SmashLogger.verbose("ignored blocked sender: ${message.sender}")
                return
            }

            // Check message body against filter list
            if (config.isFiltered(body)) {
                SmashLogger.verbose("filtered message from ${message.sender}: $body")
                return
            }

            // Track last external sender for Cmd reply — skip targets
            val cleanedSender = PhoneUtils.cleanPhone(message.sender)
            val isFromTarget = config.targets.any { PhoneUtils.cleanPhone(it) == cleanedSender }
            if (!isFromTarget) {
                replyPhoneNum = message.sender
            }

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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Check for alert conditions using AlertManager
        val activeAlerts = AlertManager.getActiveAlerts()

        if (!checking && activeAlerts.isNotEmpty()) {
            val warnings = mutableListOf<String>()
            if (activeAlerts.containsKey(AlertManager.ALERT_POWER)) {
                warnings.add("⚠️ ${activeAlerts[AlertManager.ALERT_POWER]}")
            }
            if (activeAlerts.containsKey(AlertManager.ALERT_SIGNAL)) {
                warnings.add("📵 ${activeAlerts[AlertManager.ALERT_SIGNAL]}")
            }
            if (activeAlerts.containsKey(AlertManager.ALERT_STORAGE)) {
                warnings.add("💾 ${activeAlerts[AlertManager.ALERT_STORAGE]}")
            }
            if (activeAlerts.containsKey(AlertManager.ALERT_TAILSCALE)) {
                warnings.add("🔒 ${activeAlerts[AlertManager.ALERT_TAILSCALE]}")
            }
            if (activeAlerts.containsKey(AlertManager.ALERT_ENDPOINT)) {
                warnings.add("🌐 ${activeAlerts[AlertManager.ALERT_ENDPOINT]}")
            }
            val warningText = warnings.joinToString(" | ")
            
            builder.setContentText(warningText)
            val styledText = Html.fromHtml(
                "$contentText<br><font color='#FF0000'><b>$warningText</b></font>",
                Html.FROM_HTML_MODE_COMPACT
            )
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(styledText))
        } else {
            builder.setContentText(contentText)
        }

        return builder.build()
    }

    /**
     * Update the notification (e.g., when default SMS app status changes).
     */
    fun updateNotification() {
        // Also check if we lost default SMS app status
        checkDefaultSmsAppStatus()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
}
