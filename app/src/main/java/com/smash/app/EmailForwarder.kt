package com.smash.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * JSON payload for email forwarding via Postmark.
 */
@Serializable
data class EmailForwardPayload(
    val origin: String,
    val destination_email: String,
    val body: String,
    val timestamp: Long
)

/**
 * HTTP client for forwarding SMS to email via mailEndpointUrl.
 */
object EmailForwarder {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    
    private val json = Json {
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Forward an SMS to an email address via HTTP POST.
     *
     * @param endpointUrl The mailEndpointUrl to POST to
     * @param origin The sender's phone number
     * @param destinationEmail The email address to forward to
     * @param messageBody The SMS body
     * @param timestamp The message timestamp in epoch milliseconds
     * @return true if successful (2xx response), false otherwise
     */
    fun forward(
        endpointUrl: String,
        origin: String,
        destinationEmail: String,
        messageBody: String,
        timestamp: Long
    ): Boolean {
        val payload = EmailForwardPayload(
            origin = origin,
            destination_email = destinationEmail,
            body = messageBody,
            timestamp = timestamp
        )

        val jsonBody = json.encodeToString(payload)
        
        val request = Request.Builder()
            .url(endpointUrl)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    SmashLogger.info("Email forward to $destinationEmail succeeded (${response.code})")
                    true
                } else {
                    SmashLogger.error("Email forward to $destinationEmail failed: HTTP ${response.code} - ${response.message}")
                    false
                }
            }
        } catch (e: Exception) {
            SmashLogger.error("Email forward to $destinationEmail failed: ${e.message}")
            false
        }
    }
}
