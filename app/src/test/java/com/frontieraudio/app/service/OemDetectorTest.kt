package com.frontieraudio.app.service

import android.os.Build
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OemDetectorTest {

    private fun setManufacturer(value: String) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)

        val field = Build::class.java.getDeclaredField("MANUFACTURER")
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, Build::class.java, offset, value)
    }

    // -- detect() --

    @Test
    fun `detect returns SAMSUNG for lowercase samsung`() {
        setManufacturer("samsung")
        assertEquals(OemType.SAMSUNG, OemDetector.detect())
    }

    @Test
    fun `detect returns SAMSUNG for mixed case Samsung`() {
        setManufacturer("Samsung")
        assertEquals(OemType.SAMSUNG, OemDetector.detect())
    }

    @Test
    fun `detect returns SAMSUNG for uppercase SAMSUNG`() {
        setManufacturer("SAMSUNG")
        assertEquals(OemType.SAMSUNG, OemDetector.detect())
    }

    @Test
    fun `detect returns XIAOMI for xiaomi`() {
        setManufacturer("xiaomi")
        assertEquals(OemType.XIAOMI, OemDetector.detect())
    }

    @Test
    fun `detect returns XIAOMI for Redmi`() {
        setManufacturer("Redmi")
        assertEquals(OemType.XIAOMI, OemDetector.detect())
    }

    @Test
    fun `detect returns XIAOMI for POCO`() {
        setManufacturer("POCO")
        assertEquals(OemType.XIAOMI, OemDetector.detect())
    }

    @Test
    fun `detect returns GENERIC for Google`() {
        setManufacturer("Google")
        assertEquals(OemType.GENERIC, OemDetector.detect())
    }

    @Test
    fun `detect returns GENERIC for OnePlus`() {
        setManufacturer("OnePlus")
        assertEquals(OemType.GENERIC, OemDetector.detect())
    }

    // -- getInstructions() --

    @Test
    fun `getInstructions returns Samsung instructions for SAMSUNG`() {
        val instructions = OemDetector.getInstructions(OemType.SAMSUNG)
        assertNotNull(instructions)
        assertTrue(instructions!!.title.contains("Samsung"))
        assertTrue(instructions.steps.contains("Never sleeping"))
    }

    @Test
    fun `getInstructions returns Xiaomi instructions for XIAOMI`() {
        val instructions = OemDetector.getInstructions(OemType.XIAOMI)
        assertNotNull(instructions)
        assertTrue(instructions!!.title.contains("Xiaomi"))
        assertTrue(instructions.steps.contains("Autostart"))
    }

    @Test
    fun `getInstructions returns null for GENERIC`() {
        assertNull(OemDetector.getInstructions(OemType.GENERIC))
    }
}
