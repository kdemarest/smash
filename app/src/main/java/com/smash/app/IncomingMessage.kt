package com.smash.app

import android.net.Uri

/**
 * Represents a media attachment from an MMS message.
 */
data class MediaAttachment(
    val uri: Uri,
    val mimeType: String,
    val data: ByteArray? = null  // Loaded on demand
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MediaAttachment
        return uri == other.uri && mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        return 31 * uri.hashCode() + mimeType.hashCode()
    }
}

/**
 * Represents an incoming message (SMS or MMS).
 * 
 * For SMS: body contains the text, attachments is empty
 * For MMS: body contains any text part, attachments contains media (images, etc.)
 */
data class IncomingMessage(
    val sender: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<MediaAttachment> = emptyList()
) {
    /**
     * Whether this message has any media attachments.
     */
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
    
    /**
     * Whether this message has image attachments.
     */
    val hasImages: Boolean get() = attachments.any { it.mimeType.startsWith("image/") }
    
    /**
     * Get only image attachments.
     */
    val imageAttachments: List<MediaAttachment> get() = 
        attachments.filter { it.mimeType.startsWith("image/") }
}
