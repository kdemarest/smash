package com.smash.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import java.io.File

/**
 * Receives the result of MMS send operations.
 * This is called when sendMultimediaMessage() completes.
 */
class MmsSentReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val resultCode = resultCode
        val pduPath = intent.getStringExtra("pdu_path")
        val destination = intent.getStringExtra("destination") ?: "unknown"
        
        when (resultCode) {
            Activity.RESULT_OK -> {
                SmashLogger.verbose("MmsSentReceiver: MMS sent successfully to $destination")
            }
            else -> {
                val errorMessage = when (resultCode) {
                    SmsManager.MMS_ERROR_UNSPECIFIED -> "MMS_ERROR_UNSPECIFIED"
                    SmsManager.MMS_ERROR_INVALID_APN -> "MMS_ERROR_INVALID_APN"
                    SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS"
                    SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE"
                    SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR"
                    SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY"
                    SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR"
                    SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "MMS_ERROR_NO_DATA_NETWORK"
                    else -> "Unknown error code: $resultCode"
                }
                SmashLogger.error("MmsSentReceiver: MMS send FAILED to $destination - $errorMessage (code $resultCode)")
                
                // Log HTTP status if available
                val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, -1)
                if (httpStatus != -1) {
                    SmashLogger.error("MmsSentReceiver: HTTP status: $httpStatus")
                }
            }
        }
        
        // Clean up the PDU file
        if (pduPath != null) {
            try {
                File(pduPath).delete()
            } catch (e: Exception) {
                SmashLogger.warning("MmsSentReceiver: failed to delete PDU file: ${e.message}")
            }
        }
    }
}
