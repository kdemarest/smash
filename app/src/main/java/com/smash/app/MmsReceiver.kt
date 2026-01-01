package com.smash.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import java.io.File

/**
 * Receives incoming MMS WAP_PUSH_DELIVER broadcast and initiates download.
 * 
 * As the default SMS app, we are responsible for:
 * 1. Receiving the WAP push notification (this broadcast)
 * 2. Parsing the notification to get the MMS content location URL
 * 3. Initiating the download via SmsManager.downloadMultimediaMessage()
 * 4. The actual downloaded content is then detected by MmsObserver
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        SmashLogger.verbose("MmsReceiver: WAP_PUSH_DELIVER received")
        
        // Get the raw WAP PDU data
        val pushData = intent.getByteArrayExtra("data")
        if (pushData == null) {
            SmashLogger.error("MmsReceiver: no data in WAP push")
            return
        }
        
        SmashLogger.verbose("MmsReceiver: received ${pushData.size} bytes of WAP push data")
        
        // Parse the PDU using the proven library
        val pdu = try {
            PduParser(pushData, true).parse()
        } catch (e: Exception) {
            SmashLogger.error("MmsReceiver: failed to parse PDU", e)
            return
        }
        
        if (pdu == null) {
            SmashLogger.error("MmsReceiver: PDU parse returned null")
            return
        }
        
        // Should be a NotificationInd for incoming MMS
        if (pdu.messageType != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
            SmashLogger.warning("MmsReceiver: unexpected message type ${pdu.messageType}")
            return
        }
        
        val notification = pdu as NotificationInd
        
        // Get the content location URL
        val contentLocationBytes = notification.contentLocation
        if (contentLocationBytes == null || contentLocationBytes.isEmpty()) {
            SmashLogger.error("MmsReceiver: no content location in notification")
            return
        }
        
        val contentLocation = String(contentLocationBytes)
        SmashLogger.verbose("MmsReceiver: content location: $contentLocation")
        
        // Download the MMS
        downloadMms(context, contentLocation)
    }
    
    /**
     * Initiate MMS download using SmsManager.
     */
    private fun downloadMms(context: Context, contentLocation: String) {
        try {
            val smsManager = getSmsManager(context)
            
            // Create a temp file for the downloaded PDU
            val mmsDir = File(context.cacheDir, "mms_download")
            mmsDir.mkdirs()
            val pduFile = File(mmsDir, "mms_${System.currentTimeMillis()}.pdu")
            
            // Get a content URI via FileProvider for the system to write to
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pduFile
            )
            
            // Grant write permission to the MMS system service
            context.grantUriPermission(
                "com.android.mms.service",
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            
            // Create a pending intent for download result notification
            val downloadIntent = Intent("com.smash.app.MMS_DOWNLOADED").apply {
                setPackage(context.packageName)
                putExtra("pdu_path", pduFile.absolutePath)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                downloadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            SmashLogger.verbose("MmsReceiver: initiating download from $contentLocation to $contentUri")
            
            smsManager.downloadMultimediaMessage(
                context,
                contentLocation,
                contentUri,
                null,  // configOverrides - use default APN settings
                pendingIntent
            )
            
            SmashLogger.verbose("MmsReceiver: download request sent")
            
        } catch (e: SecurityException) {
            SmashLogger.error("MmsReceiver: download failed - permission denied", e)
        } catch (e: Exception) {
            SmashLogger.error("MmsReceiver: download failed", e)
        }
    }
    
    @Suppress("DEPRECATION")
    private fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
}
