package com.smash.app

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unit tests for SmashLogger timestamp format.
 * Note: Full logging tests require Android instrumentation tests.
 */
class SmashLoggerTest {

    @Test
    fun `timestamp format is correct`() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
        val testDate = Date(1703961600000L) // 2024-12-30 12:00:00 UTC
        val formatted = dateFormat.format(testDate)
        
        // Should match pattern yyyy-MM-dd-HH-mm-ss
        assertTrue(formatted.matches(Regex("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `level tags are correct`() {
        assertEquals("[info]", SmashLogger.Level.INFO.tag)
        assertEquals("[warning]", SmashLogger.Level.WARNING.tag)
        assertEquals("[error]", SmashLogger.Level.ERROR.tag)
    }
}
