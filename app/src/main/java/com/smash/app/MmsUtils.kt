package com.smash.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Utility for sending MMS messages with images.
 * Uses SmsManager.sendMultimediaMessage() directly with PDU composition.
 * 
 * MMS Limitations handled:
 * - Total message size: ~300KB (conservative for all carriers)
 * - Image dimensions: 640x640 max (resized if larger)
 * - Image format: JPEG only (PNG/GIF/HEIC converted)
 * - Attachment count: 3 max (additional images dropped)
 * - Text length: 5000 chars max (truncated)
 * - Video/audio: Not supported (skipped with warning)
 */
object MmsUtils {

    // Default max image dimensions (carrier limits vary, but 640x640 is safe)
    private const val MAX_IMAGE_WIDTH = 640
    private const val MAX_IMAGE_HEIGHT = 640
    
    // Max compressed size per image (~100KB is safe for most carriers)
    private const val MAX_IMAGE_BYTES = 100 * 1024
    
    // Max total MMS size (AT&T supports 1MB)
    private const val MAX_TOTAL_MMS_BYTES = 1024 * 1024
    
    // Max number of images per MMS (AT&T supports 10)
    private const val MAX_IMAGES_PER_MMS = 10
    
    // Max text length in MMS
    private const val MAX_TEXT_LENGTH = 5000
    
    // Stale PDU temp file age (1 hour) - files older than this are cleaned up
    private const val STALE_PDU_FILE_AGE_MS = 60 * 60 * 1000L
    
    // Supported image MIME types for MMS (carriers support these natively)
    private val SUPPORTED_MMS_IMAGE_TYPES = setOf(
        "image/jpeg",
        "image/png", 
        "image/gif"
    )
    
    // Image types that need conversion to JPEG
    private val CONVERTIBLE_IMAGE_TYPES = setOf(
        "image/heic",
        "image/heif",
        "image/webp",
        "image/bmp"
    )
    
    private const val FILE_PROVIDER_AUTHORITY = "com.smash.app.fileprovider"

    /**
     * Filter attachments to only those with supported image MIME types.
     * Skips video, audio, vCard, and other non-image types.
     */
    private fun filterValidAttachments(attachments: List<MediaAttachment>): List<MediaAttachment> {
        return attachments.filter { attachment ->
            val mimeType = attachment.mimeType.lowercase()
            when {
                SUPPORTED_MMS_IMAGE_TYPES.contains(mimeType) -> true
                CONVERTIBLE_IMAGE_TYPES.contains(mimeType) -> true
                mimeType.startsWith("image/") -> {
                    SmashLogger.warning("MmsUtils: unsupported image type $mimeType, will attempt conversion")
                    true
                }
                else -> {
                    SmashLogger.warning("MmsUtils: skipping non-image attachment: $mimeType")
                    false
                }
            }
        }
    }

    /**
     * Send an MMS message with images to the specified phone number.
     * 
     * Applies MMS limitations:
     * - Filters to supported image types only
     * - Limits to MAX_IMAGES_PER_MMS attachments
     * - Truncates text to MAX_TEXT_LENGTH
     * - Compresses images to fit within MAX_TOTAL_MMS_BYTES
     * 
     * @param context Android context
     * @param destination Phone number to send to
     * @param messageText Text part of the MMS (can be empty)
     * @param imageAttachments List of image attachments to include
     * @return true if send was initiated successfully, false on error
     */
    fun sendMms(
        context: Context,
        destination: String,
        messageText: String,
        imageAttachments: List<MediaAttachment>
    ): Boolean {
        var cleanedNumber = PhoneUtils.cleanPhone(destination)
        if (cleanedNumber.isEmpty()) {
            SmashLogger.error("sendMms failed: empty destination after cleaning '$destination'")
            return false
        }
        
        // Ensure country code for US numbers (MMS requires it)
        if (cleanedNumber.length == 10 && !cleanedNumber.startsWith("+")) {
            cleanedNumber = "+1$cleanedNumber"
            SmashLogger.verbose("MmsUtils: added +1 country code -> $cleanedNumber")
        } else if (cleanedNumber.length == 11 && cleanedNumber.startsWith("1") && !cleanedNumber.startsWith("+")) {
            cleanedNumber = "+$cleanedNumber"
            SmashLogger.verbose("MmsUtils: added + prefix -> $cleanedNumber")
        }

        // Filter to MMS-compatible image attachments only
        val validAttachments = filterValidAttachments(imageAttachments)
        if (validAttachments.isEmpty()) {
            SmashLogger.error("sendMms failed: no valid image attachments after filtering")
            return false
        }
        
        // Limit number of attachments
        val limitedAttachments = if (validAttachments.size > MAX_IMAGES_PER_MMS) {
            SmashLogger.warning("MmsUtils: limiting from ${validAttachments.size} to $MAX_IMAGES_PER_MMS images")
            validAttachments.take(MAX_IMAGES_PER_MMS)
        } else {
            validAttachments
        }
        
        // Truncate text if needed
        val truncatedText = if (messageText.length > MAX_TEXT_LENGTH) {
            SmashLogger.warning("MmsUtils: truncating text from ${messageText.length} to $MAX_TEXT_LENGTH chars")
            messageText.take(MAX_TEXT_LENGTH - 3) + "..."
        } else {
            messageText
        }

        return try {
            // Build the MMS PDU
            val pduBytes = buildMmsPdu(context, cleanedNumber, truncatedText, limitedAttachments)
            if (pduBytes == null) {
                SmashLogger.error("sendMms failed: could not build PDU")
                return false
            }
            
            // Check total size
            if (pduBytes.size > MAX_TOTAL_MMS_BYTES) {
                SmashLogger.warning("MmsUtils: PDU size ${pduBytes.size} exceeds limit $MAX_TOTAL_MMS_BYTES, carrier may reject")
            }
            
            SmashLogger.verbose("MmsUtils: built PDU of ${pduBytes.size} bytes")
            
            // Clean up stale PDU files before creating new one
            cleanupStalePduFiles(context)
            
            // Write PDU to file for the system to read
            val pduFile = File(context.cacheDir, "mms_send/mms_send_${System.currentTimeMillis()}.pdu")
            pduFile.parentFile?.mkdirs()
            pduFile.writeBytes(pduBytes)
            
            // Get content URI via FileProvider
            val contentUri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, pduFile)
            
            // Create sent intent to get callback
            val sentIntent = Intent(context, MmsSentReceiver::class.java).apply {
                putExtra("pdu_path", pduFile.absolutePath)
                putExtra("destination", cleanedNumber)
            }
            val sentPendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Send via SmsManager
            val smsManager = getSmsManager(context)
            smsManager.sendMultimediaMessage(
                context,
                contentUri,
                null,  // locationUrl - not needed for sending
                null,  // configOverrides
                sentPendingIntent
            )
            
            SmashLogger.verbose("MmsUtils: MMS send initiated to $cleanedNumber with ${limitedAttachments.size} image(s)")
            true
        } catch (e: SecurityException) {
            SmashLogger.error("MMS FAILED to $cleanedNumber: Permission denied - ${e.message}")
            false
        } catch (e: Exception) {
            SmashLogger.error("MMS FAILED to $cleanedNumber: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    /**
     * Build an MMS PDU (SendReq) with text and images.
     */
    private fun buildMmsPdu(
        context: Context,
        destination: String,
        text: String,
        imageAttachments: List<MediaAttachment>
    ): ByteArray? {
        return try {
            val sendReq = SendReq()
            
            // Generate a unique transaction ID
            val transactionId = "Smash${System.currentTimeMillis()}"
            sendReq.transactionId = transactionId.toByteArray()
            
            // Set MMS version (1.2 = 0x12)
            sendReq.mmsVersion = PduHeaders.MMS_VERSION_1_2
            
            // Set date
            sendReq.date = System.currentTimeMillis() / 1000
            
            // Set from address (use special token that carrier will replace)
            val fromAddress = EncodedStringValue("insert-address-token".toByteArray())
            sendReq.from = fromAddress
            
            // Set recipient
            val recipientAddress = EncodedStringValue(destination)
            sendReq.addTo(recipientAddress)
            
            // Set content type for multipart/related
            sendReq.contentType = "application/vnd.wap.multipart.related".toByteArray()
            
            // Build the body with parts
            val body = PduBody()
            
            // Add text part if present
            if (text.isNotEmpty()) {
                val textPart = PduPart()
                textPart.contentType = "text/plain; charset=utf-8".toByteArray()
                textPart.contentId = "<text>".toByteArray()
                textPart.contentLocation = "text.txt".toByteArray()
                textPart.charset = CharacterSets.UTF_8
                textPart.data = text.toByteArray(Charsets.UTF_8)
                body.addPart(textPart)
                SmashLogger.verbose("MmsUtils: added text part: ${text.length} chars")
            }
            
            // Add image parts
            var imageIndex = 0
            for (attachment in imageAttachments) {
                val imageData = attachment.data ?: continue
                
                // Compress the image
                val compressedData = compressImage(imageData)
                if (compressedData == null) {
                    SmashLogger.warning("MmsUtils: skipping uncompressible image")
                    continue
                }
                
                val imagePart = PduPart()
                imagePart.contentType = "image/jpeg".toByteArray()
                imagePart.contentId = "<image$imageIndex>".toByteArray()
                imagePart.contentLocation = "image$imageIndex.jpg".toByteArray()
                imagePart.name = "image$imageIndex.jpg".toByteArray()
                imagePart.data = compressedData
                body.addPart(imagePart)
                
                SmashLogger.verbose("MmsUtils: added image part $imageIndex: ${compressedData.size} bytes")
                imageIndex++
            }
            
            if (body.partsNum == 0) {
                SmashLogger.error("MmsUtils: no parts added to PDU body")
                return null
            }
            
            sendReq.body = body
            
            SmashLogger.verbose("MmsUtils: PDU has ${body.partsNum} parts, composing...")
            
            // Compose to bytes
            val pduBytes = PduComposer(context, sendReq).make()
            if (pduBytes == null) {
                SmashLogger.error("MmsUtils: PduComposer.make() returned null")
                return null
            }
            
            SmashLogger.verbose("MmsUtils: PDU composed successfully: ${pduBytes.size} bytes")
            pduBytes
        } catch (e: Exception) {
            SmashLogger.error("MmsUtils: PDU composition failed", e)
            null
        }
    }

    /**
     * Compress and resize an image to fit within MMS size limits.
     */
    private fun compressImage(imageData: ByteArray): ByteArray? {
        return try {
            // First, decode just bounds to calculate scaling
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
            
            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            
            if (originalWidth <= 0 || originalHeight <= 0) {
                SmashLogger.error("MmsUtils: invalid image dimensions ${originalWidth}x${originalHeight}")
                return null
            }
            
            // Calculate sample size for initial downscaling (power of 2)
            var sampleSize = 1
            if (originalWidth > MAX_IMAGE_WIDTH || originalHeight > MAX_IMAGE_HEIGHT) {
                val widthRatio = originalWidth.toFloat() / MAX_IMAGE_WIDTH
                val heightRatio = originalHeight.toFloat() / MAX_IMAGE_HEIGHT
                sampleSize = maxOf(widthRatio, heightRatio).toInt().coerceAtLeast(1)
                // Round up to nearest power of 2 for efficiency
                sampleSize = Integer.highestOneBit(sampleSize)
                if (sampleSize < maxOf(widthRatio, heightRatio).toInt()) {
                    sampleSize *= 2
                }
            }
            
            // Decode with downsampling
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions)
                ?: return null
            
            // Further resize if still too large
            val scaledBitmap = if (bitmap.width > MAX_IMAGE_WIDTH || bitmap.height > MAX_IMAGE_HEIGHT) {
                val scale = minOf(
                    MAX_IMAGE_WIDTH.toFloat() / bitmap.width,
                    MAX_IMAGE_HEIGHT.toFloat() / bitmap.height
                )
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
            
            // Compress to JPEG with quality adjustment to hit target size
            var quality = 85
            var compressed: ByteArray
            
            do {
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                compressed = outputStream.toByteArray()
                
                if (compressed.size > MAX_IMAGE_BYTES && quality > 20) {
                    quality -= 10
                } else {
                    break
                }
            } while (quality > 20)
            
            scaledBitmap.recycle()
            
            SmashLogger.verbose("MmsUtils: compressed ${originalWidth}x${originalHeight} -> ${compressed.size} bytes at quality $quality")
            compressed
        } catch (e: Exception) {
            SmashLogger.error("MmsUtils: image compression failed", e)
            null
        }
    }

    /**
     * Get the SmsManager instance.
     */
    @Suppress("DEPRECATION")
    private fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
    
    /**
     * Clean up stale PDU temp files older than STALE_PDU_FILE_AGE_MS (1 hour).
     * Called before creating new PDU files to prevent accumulation.
     */
    private fun cleanupStalePduFiles(context: Context) {
        try {
            val pduDir = File(context.cacheDir, "mms_send")
            if (!pduDir.exists()) return
            
            val cutoffTime = System.currentTimeMillis() - STALE_PDU_FILE_AGE_MS
            var deletedCount = 0
            
            pduDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
            
            if (deletedCount > 0) {
                SmashLogger.verbose("MmsUtils: cleaned up $deletedCount stale PDU file(s)")
            }
        } catch (e: Exception) {
            SmashLogger.warning("MmsUtils: PDU cleanup failed: ${e.message}")
        }
    }
}
