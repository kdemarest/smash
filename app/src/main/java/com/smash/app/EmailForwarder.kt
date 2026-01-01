package com.smash.app

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Represents an image attachment for the email payload.
 */
@Serializable
data class ImagePayload(
    val mimeType: String,
    val data: String  // Base64 encoded
)

/**
 * JSON payload for email forwarding via mailEndpointUrl.
 * Now supports optional image attachments.
 */
@Serializable
data class EmailForwardPayload(
    val origin: String,
    val destination_email: String,
    val body: String,
    val timestamp: Long,
    val images: List<ImagePayload>? = null
)

/**
 * HTTP client for forwarding SMS/MMS to email via mailEndpointUrl.
 */
object EmailForwarder {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    
    private val json = Json {
        encodeDefaults = false  // Don't include null images array
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // Longer timeout for image uploads
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Forward an SMS to an email address via HTTP POST (no attachments).
     */
    fun forward(
        endpointUrl: String,
        origin: String,
        destinationEmail: String,
        messageBody: String,
        timestamp: Long
    ): Boolean {
        return forwardWithImages(
            endpointUrl = endpointUrl,
            origin = origin,
            destinationEmail = destinationEmail,
            messageBody = messageBody,
            timestamp = timestamp,
            images = null
        )
    }

    /**
     * Forward an MMS to an email address via HTTP POST, including image attachments.
     *
     * @param context Android context for reading attachment URIs
     * @param endpointUrl The mailEndpointUrl to POST to
     * @param origin The sender's phone number
     * @param destinationEmail The email address to forward to
     * @param messageBody The message text
     * @param timestamp The message timestamp in epoch milliseconds
     * @param attachments List of media attachments (images)
     * @return true if successful (2xx response), false otherwise
     */
    fun forwardWithAttachments(
        context: Context,
        endpointUrl: String,
        origin: String,
        destinationEmail: String,
        messageBody: String,
        timestamp: Long,
        attachments: List<MediaAttachment>
    ): Boolean {
        // Convert attachments to base64
        val images = attachments.mapNotNull { attachment ->
            try {
                // Prefer pre-loaded data if available (from PDU parsing)
                val bytes = attachment.data 
                    ?: context.contentResolver.openInputStream(attachment.uri)?.use { 
                        it.readBytes() 
                    }
                if (bytes != null) {
                    ImagePayload(
                        mimeType = attachment.mimeType,
                        data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    )
                } else {
                    SmashLogger.warning("Could not read attachment: ${attachment.uri}")
                    null
                }
            } catch (e: Exception) {
                SmashLogger.error("Failed to read attachment ${attachment.uri}", e)
                null
            }
        }

        return forwardWithImages(
            endpointUrl = endpointUrl,
            origin = origin,
            destinationEmail = destinationEmail,
            messageBody = messageBody,
            timestamp = timestamp,
            images = images.ifEmpty { null }
        )
    }

    /**
     * Internal: forward with optional images payload.
     */
    private fun forwardWithImages(
        endpointUrl: String,
        origin: String,
        destinationEmail: String,
        messageBody: String,
        timestamp: Long,
        images: List<ImagePayload>?
    ): Boolean {
        val payload = EmailForwardPayload(
            origin = origin,
            destination_email = destinationEmail,
            body = messageBody,
            timestamp = timestamp,
            images = images
        )

        val jsonBody = json.encodeToString(payload)
        val payloadSize = jsonBody.length
        val imageInfo = if (images != null) " (${images.size} images, ${payloadSize / 1024}KB payload)" else ""
        
        // Debug: log image details
        if (images != null) {
            for ((idx, img) in images.withIndex()) {
                SmashLogger.verbose("EmailForwarder: image[$idx] mimeType=${img.mimeType}, base64 length=${img.data.length}")
            }
            SmashLogger.verbose("EmailForwarder: total JSON payload size=${payloadSize} bytes")
        }
        
        val request = try {
            Request.Builder()
                .url(endpointUrl)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .build()
        } catch (e: Exception) {
            SmashLogger.error("FORWARD FAILED to $destinationEmail: URL '$endpointUrl' is invalid - ${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    if (images != null) {
                        SmashLogger.verbose("Forwarded to $destinationEmail with ${images.size} images")
                    }
                    true
                } else {
                    val responseBody = try { response.body?.string()?.take(200) } catch (e: Exception) { null }
                    SmashLogger.error("FORWARD FAILED to $destinationEmail$imageInfo: HTTP ${response.code} ${response.message} from $endpointUrl" + 
                        if (responseBody.isNullOrBlank()) "" else " - Response: $responseBody")
                    false
                }
            }
        } catch (e: java.net.UnknownHostException) {
            SmashLogger.error("FORWARD FAILED to $destinationEmail: Cannot resolve host for '$endpointUrl' - check URL or internet connection")
            false
        } catch (e: java.net.ConnectException) {
            SmashLogger.error("FORWARD FAILED to $destinationEmail: Cannot connect to '$endpointUrl' - ${e.message}")
            false
        } catch (e: java.net.SocketTimeoutException) {
            SmashLogger.error("FORWARD FAILED to $destinationEmail: Connection timed out to '$endpointUrl'")
            false
        } catch (e: javax.net.ssl.SSLException) {
            SmashLogger.error("FORWARD FAILED to $destinationEmail: SSL/TLS error for '$endpointUrl' - ${e.message}")
            false
        } catch (e: Exception) {
            SmashLogger.error("FORWARD FAILED to $destinationEmail: ${e.javaClass.simpleName}: ${e.message} - URL was '$endpointUrl'")
            false
        }
    }
}
