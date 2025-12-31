package com.smash.app

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony

/**
 * Utility functions for phone number handling and SMS checks.
 */
object PhoneUtils {

    /**
     * Check if the app is the default SMS app.
     * Uses RoleManager on Android Q+ for reliability.
     */
    fun isDefaultSmsApp(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            } else {
                Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clean a phone number by removing everything except:
     * - Leading plus sign (+)
     * - Digits (0-9)
     *
     * Example: "(555) 123-4567" -> "5551234567"
     * Example: "+1 (555) 123-4567" -> "+15551234567"
     */
    fun cleanPhone(number: String): String {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return ""

        val result = StringBuilder()
        var isFirst = true

        for (char in trimmed) {
            when {
                isFirst && char == '+' -> {
                    result.append(char)
                    isFirst = false
                }
                char.isDigit() -> {
                    result.append(char)
                    isFirst = false
                }
            }
        }

        return result.toString()
    }

    /**
     * Check if a string looks like an email address.
     * Simple check: contains '@'
     */
    fun isEmail(value: String): Boolean {
        return value.contains('@')
    }

    /**
     * Check if a string looks like a phone number.
     * Simple check: does NOT contain '@'
     */
    fun isPhone(value: String): Boolean {
        return !isEmail(value)
    }
}
