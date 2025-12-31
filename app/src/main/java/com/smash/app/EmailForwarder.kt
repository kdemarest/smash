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
 * JSON payload for email forwarding via mailEndpointUrl.
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
                    //SmashLogger.info("Forwarded to $destinationEmail via $endpointUrl (HTTP ${response.code})")
                    true
                } else {
                    val responseBody = try { response.body?.string()?.take(200) } catch (e: Exception) { null }
                    SmashLogger.error("FORWARD FAILED to $destinationEmail: HTTP ${response.code} ${response.message} from $endpointUrl" + 
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
