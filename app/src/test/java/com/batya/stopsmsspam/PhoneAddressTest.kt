package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.PhoneAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAddressTest {

    @Test
    fun `groups the same number written different ways`() {
        val forms = listOf("+1 555-123-4567", "15551234567", "(555) 123-4567", "555.123.4567")
        val keys = forms.map { PhoneAddress.normalize(it) }.distinct()
        assertEquals(1, keys.size)
    }

    @Test
    fun `keeps short codes intact instead of truncating them`() {
        assertEquals("22395", PhoneAddress.normalize("22395"))
        assertEquals("262966", PhoneAddress.normalize("262966"))
    }

    @Test
    fun `does not merge two different short codes`() {
        assertNotEquals(PhoneAddress.normalize("22395"), PhoneAddress.normalize("22396"))
    }

    @Test
    fun `keeps alphanumeric sender ids verbatim`() {
        assertEquals("AMZN", PhoneAddress.normalize("AMZN"))
        assertEquals("VERIZON", PhoneAddress.normalize("Verizon"))
    }

    @Test
    fun `recognises short codes, which Android may gate behind a confirmation dialog`() {
        assertTrue(PhoneAddress.isShortCode("22395"))
        assertTrue(PhoneAddress.isShortCode("262966"))
        assertFalse(PhoneAddress.isShortCode("+15551234567"))
        assertFalse(PhoneAddress.isShortCode("5551234567"))
        assertFalse(PhoneAddress.isShortCode("AMZN"))
        assertFalse(PhoneAddress.isShortCode(""))
    }

    @Test
    fun `keeps distinct numbers distinct`() {
        assertNotEquals(
            PhoneAddress.normalize("+15551234567"),
            PhoneAddress.normalize("+15559999999"),
        )
    }
}
