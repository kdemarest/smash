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
     * Checks alias first, then contacts, falls back to raw number.
     * 
     * @param context Android context
     * @param phoneNumber The phone number
     * @param config The SmashConfig with aliases
     * @return Display name (alias, contact name, or phone number)
     */
    fun getDisplayName(context: Context, phoneNumber: String, config: SmashConfig): String {
        // First check aliases
        config.findAliasName(phoneNumber)?.let { return it }
        
        // Then check contacts
        lookupName(context, phoneNumber)?.let { return it }
        
        // Fall back to phone number
        return phoneNumber
    }

    /**
     * Get a short display name for a phone number (first name only for contacts).
     * Checks alias first, then contacts (first name only), falls back to raw number.
     * 
     * @param context Android context
     * @param phoneNumber The phone number
     * @param config The SmashConfig with aliases
     * @return Short display name (alias, first name, or phone number)
     */
    fun getDisplayNameShort(context: Context, phoneNumber: String, config: SmashConfig): String {
        // First check aliases (use as-is)
        config.findAliasName(phoneNumber)?.let { return it }
        
        // Then check contacts - use first name only
        lookupName(context, phoneNumber)?.let { fullName ->
            return fullName.split(" ").firstOrNull() ?: fullName
        }
        
        // Fall back to phone number
        return phoneNumber
    }
}
