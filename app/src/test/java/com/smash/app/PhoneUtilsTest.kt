package com.smash.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PhoneUtils.
 */
class PhoneUtilsTest {

    @Test
    fun `cleanPhone removes parentheses and dashes`() {
        assertEquals("5551234567", PhoneUtils.cleanPhone("(555) 123-4567"))
    }

    @Test
    fun `cleanPhone preserves leading plus`() {
        assertEquals("+15551234567", PhoneUtils.cleanPhone("+1 (555) 123-4567"))
    }

    @Test
    fun `cleanPhone removes all non-digits except leading plus`() {
        assertEquals("+12345", PhoneUtils.cleanPhone("+1-2-3-4-5"))
    }

    @Test
    fun `cleanPhone handles plus in middle - only keeps leading plus`() {
        assertEquals("+123456", PhoneUtils.cleanPhone("+123+456"))
    }

    @Test
    fun `cleanPhone handles empty string`() {
        assertEquals("", PhoneUtils.cleanPhone(""))
    }

    @Test
    fun `cleanPhone handles whitespace only`() {
        assertEquals("", PhoneUtils.cleanPhone("   "))
    }

    @Test
    fun `cleanPhone handles no digits`() {
        assertEquals("", PhoneUtils.cleanPhone("abc"))
    }

    @Test
    fun `cleanPhone trims whitespace`() {
        assertEquals("123", PhoneUtils.cleanPhone("  123  "))
    }

    @Test
    fun `cleanPhone handles international format`() {
        assertEquals("+441234567", PhoneUtils.cleanPhone("+44 (1) 234-567"))
    }

    @Test
    fun `isEmail detects at sign`() {
        assertTrue(PhoneUtils.isEmail("test@example.com"))
        assertTrue(PhoneUtils.isEmail("a@b"))
        assertFalse(PhoneUtils.isEmail("+15551234567"))
        assertFalse(PhoneUtils.isEmail("notanemail"))
    }

    @Test
    fun `isPhone is opposite of isEmail`() {
        assertFalse(PhoneUtils.isPhone("test@example.com"))
        assertTrue(PhoneUtils.isPhone("+15551234567"))
    }
}
