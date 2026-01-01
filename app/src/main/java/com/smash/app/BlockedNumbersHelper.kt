package com.smash.app

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract

/**
 * Helper to access system-level blocked numbers.
 * These are shared by Google Messages, Phone app, and other default apps.
 * Requires being the default SMS app or default dialer.
 */
object BlockedNumbersHelper {

    enum class BlockResult {
        SUCCESS,
        ALREADY_EXISTS,
        NOT_FOUND,
        NO_ACCESS,
        ERROR
    }

    /**
     * Check if we can access blocked numbers (requires being default SMS app).
     */
    fun canAccessBlockedNumbers(context: Context): Boolean {
        return BlockedNumberContract.canCurrentUserBlockNumbers(context)
    }

    /**
     * Block a phone number.
     */
    fun blockNumber(context: Context, number: String): BlockResult {
        if (!canAccessBlockedNumbers(context)) {
            return BlockResult.NO_ACCESS
        }

        val cleaned = PhoneUtils.cleanPhone(number)
        if (cleaned.isEmpty()) return BlockResult.ERROR

        // Check if already blocked
        if (isBlocked(context, cleaned)) {
            return BlockResult.ALREADY_EXISTS
        }

        return try {
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, cleaned)
            }
            val uri = context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )
            if (uri != null) BlockResult.SUCCESS else BlockResult.ERROR
        } catch (e: Exception) {
            SmashLogger.error("BlockedNumbersHelper: blockNumber failed", e)
            BlockResult.ERROR
        }
    }

    /**
     * Unblock a phone number.
     */
    fun unblockNumber(context: Context, number: String): BlockResult {
        if (!canAccessBlockedNumbers(context)) {
            return BlockResult.NO_ACCESS
        }

        val cleaned = PhoneUtils.cleanPhone(number)
        if (cleaned.isEmpty()) return BlockResult.ERROR

        // Check if it exists
        if (!isBlocked(context, cleaned)) {
            return BlockResult.NOT_FOUND
        }

        return try {
            val deleted = context.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                arrayOf(cleaned)
            )
            if (deleted > 0) BlockResult.SUCCESS else BlockResult.NOT_FOUND
        } catch (e: Exception) {
            SmashLogger.error("BlockedNumbersHelper: unblockNumber failed", e)
            BlockResult.ERROR
        }
    }

    /**
     * Get all system-blocked numbers.
     * Returns empty list if we don't have access.
     */
    fun getBlockedNumbers(context: Context): List<String> {
        if (!canAccessBlockedNumbers(context)) {
            SmashLogger.warning("BlockedNumbersHelper: cannot access blocked numbers (not default SMS app?)")
            return emptyList()
        }

        val blocked = mutableListOf<String>()
        try {
            val cursor = context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                null
            )
            
            cursor?.use {
                val numberIndex = it.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
                while (it.moveToNext()) {
                    val number = it.getString(numberIndex)
                    if (!number.isNullOrBlank()) {
                        blocked.add(number)
                    }
                }
            }
        } catch (e: Exception) {
            SmashLogger.error("BlockedNumbersHelper: failed to query blocked numbers", e)
        }

        return blocked
    }

    /**
     * Check if a number is blocked at the system level.
     */
    fun isBlocked(context: Context, number: String): Boolean {
        if (!canAccessBlockedNumbers(context)) return false
        return try {
            BlockedNumberContract.isBlocked(context, number)
        } catch (e: Exception) {
            SmashLogger.error("BlockedNumbersHelper: isBlocked failed", e)
            false
        }
    }

    /**
     * Get the N most recently blocked numbers (by _id descending).
     * Note: BlockedNumberContract has no timestamp, but _id auto-increments.
     */
    fun getBlockedNumbersRecent(context: Context, limit: Int): List<String> {
        if (!canAccessBlockedNumbers(context)) {
            return emptyList()
        }

        val blocked = mutableListOf<String>()
        try {
            val cursor = context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ID} DESC"
            )
            
            cursor?.use {
                val numberIndex = it.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val number = it.getString(numberIndex)
                    if (!number.isNullOrBlank()) {
                        blocked.add(number)
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            SmashLogger.error("BlockedNumbersHelper: failed to query blocked numbers", e)
        }

        return blocked
    }

    /**
     * Get total count of system-blocked numbers.
     */
    fun getBlockedCount(context: Context): Int {
        if (!canAccessBlockedNumbers(context)) return 0

        return try {
            val cursor = context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ID),
                null,
                null,
                null
            )
            cursor?.use { it.count } ?: 0
        } catch (e: Exception) {
            SmashLogger.error("BlockedNumbersHelper: failed to count blocked numbers", e)
            0
        }
    }
}
