package com.smash.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SmashConfig.
 */
class SmashConfigTest {

    @Test
    fun `default config has correct values`() {
        val config = SmashConfig.default()
        assertEquals("Cmd", config.prefix)
        assertNull(config.mailEndpointUrl)
        assertTrue(config.targets.isEmpty())
    }

    @Test
    fun `fromJson parses valid json`() {
        val json = """
            {
                "prefix": "Test",
                "mailEndpointUrl": "https://example.com",
                "targets": ["target1", "target2"]
            }
        """.trimIndent()

        val config = SmashConfig.fromJson(json)
        assertEquals("Test", config.prefix)
        assertEquals("https://example.com", config.mailEndpointUrl)
        assertEquals(listOf("target1", "target2"), config.targets)
    }

    @Test
    fun `fromJson returns default on invalid json`() {
        val config = SmashConfig.fromJson("invalid json")
        assertEquals("Cmd", config.prefix)
        assertNull(config.mailEndpointUrl)
        assertTrue(config.targets.isEmpty())
    }

    @Test
    fun `toJson produces valid json`() {
        val config = SmashConfig(
            prefix = "MyPrefix",
            mailEndpointUrl = "https://test.com",
            targets = listOf("a", "b")
        )
        val json = SmashConfig.toJson(config)
        
        // Parse it back to verify
        val parsed = SmashConfig.fromJson(json)
        assertEquals(config, parsed)
    }

    @Test
    fun `withValidPrefix replaces empty prefix`() {
        val config = SmashConfig(prefix = "", mailEndpointUrl = null, targets = emptyList())
        val valid = config.withValidPrefix()
        assertEquals("Cmd", valid.prefix)
    }

    @Test
    fun `withValidPrefix replaces blank prefix`() {
        val config = SmashConfig(prefix = "   ", mailEndpointUrl = null, targets = emptyList())
        val valid = config.withValidPrefix()
        assertEquals("Cmd", valid.prefix)
    }

    @Test
    fun `withValidPrefix keeps non-empty prefix`() {
        val config = SmashConfig(prefix = "MyCmd", mailEndpointUrl = null, targets = emptyList())
        val valid = config.withValidPrefix()
        assertEquals("MyCmd", valid.prefix)
    }

    @Test
    fun `addTarget adds new target`() {
        val config = SmashConfig.default()
        val (newConfig, wasAdded) = config.addTarget("test@example.com")
        assertTrue(wasAdded)
        assertEquals(listOf("test@example.com"), newConfig.targets)
    }

    @Test
    fun `addTarget detects duplicate case-insensitive`() {
        val config = SmashConfig(prefix = "Cmd", mailEndpointUrl = null, targets = listOf("Test@Example.com"))
        val (newConfig, wasAdded) = config.addTarget("test@example.com")
        assertFalse(wasAdded)
        assertEquals(listOf("Test@Example.com"), newConfig.targets)
    }

    @Test
    fun `addTarget preserves original case`() {
        val config = SmashConfig.default()
        val (newConfig, _) = config.addTarget("TEST@EXAMPLE.COM")
        assertEquals(listOf("TEST@EXAMPLE.COM"), newConfig.targets)
    }

    @Test
    fun `removeTarget removes existing target case-insensitive`() {
        val config = SmashConfig(prefix = "Cmd", mailEndpointUrl = null, targets = listOf("Test@Example.com"))
        val (newConfig, wasRemoved) = config.removeTarget("test@example.com")
        assertTrue(wasRemoved)
        assertTrue(newConfig.targets.isEmpty())
    }

    @Test
    fun `removeTarget returns false when not found`() {
        val config = SmashConfig(prefix = "Cmd", mailEndpointUrl = null, targets = listOf("a@b.com"))
        val (newConfig, wasRemoved) = config.removeTarget("notfound@example.com")
        assertFalse(wasRemoved)
        assertEquals(listOf("a@b.com"), newConfig.targets)
    }

    @Test
    fun `isMailEndpointEnabled returns false for null`() {
        val config = SmashConfig(prefix = "Cmd", mailEndpointUrl = null, targets = emptyList())
        assertFalse(config.isMailEndpointEnabled())
    }

    @Test
    fun `isMailEndpointEnabled returns false for empty string`() {
        val config = SmashConfig(prefix = "Cmd", mailEndpointUrl = "", targets = emptyList())
        assertFalse(config.isMailEndpointEnabled())
    }

    @Test
    fun `isMailEndpointEnabled returns true for valid url`() {
        val config = SmashConfig(prefix = "Cmd", mailEndpointUrl = "https://example.com", targets = emptyList())
        assertTrue(config.isMailEndpointEnabled())
    }

    @Test
    fun `isEmail detects at sign`() {
        val config = SmashConfig.default()
        assertTrue(config.isEmail("test@example.com"))
        assertFalse(config.isEmail("+15551234567"))
    }
}
