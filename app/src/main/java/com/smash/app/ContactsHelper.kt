package com.smash.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Helper for looking up contact names from phone numbers.
 */
object ContactsHelper {

    /**
     * Look up a contact name by phone number.
     * 
     * @param context Android context
     * @param phoneNumber The phone number to look up
     * @return The contact name if found, null otherwise
     */
    fun lookupName(context: Context, phoneNumber: String): String? {
        val cleanedNumber = PhoneUtils.cleanPhone(phoneNumber)
        if (cleanedNumber.isEmpty()) {
            return null
        }

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleanedNumber)
            )
            
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        } catch (e: SecurityException) {
            // Permission not granted
            SmashLogger.warning("Contacts permission not granted")
            null
        } catch (e: Exception) {
            SmashLogger.warning("Contact lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Get a display name for a phone number.
     * Checks alias first, then contacts (full name + number), falls back to raw number.
     */
    fun getDisplayName(context: Context, phoneNumber: String, config: SmashConfig): String {
        config.findAliasName(phoneNumber)?.let { return it }
        lookupName(context, phoneNumber)?.let { fullName ->
            return "$fullName $phoneNumber"
        }
        return phoneNumber
    }

    /**
     * Get a display name for a phone number (phone targets).
     * Checks alias first, then contacts (full name + number), falls back to raw number.
     */
    fun getDisplayNameShort(context: Context, phoneNumber: String, config: SmashConfig): String {
        config.findAliasName(phoneNumber)?.let { return it }
        lookupName(context, phoneNumber)?.let { fullName ->
            return "$fullName $phoneNumber"
        }
        return phoneNumber
    }
}
