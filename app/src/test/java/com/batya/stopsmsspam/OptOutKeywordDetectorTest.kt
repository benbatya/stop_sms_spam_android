package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.OptOutKeywordDetector
import com.batya.stopsmsspam.data.model.KeywordConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptOutKeywordDetectorTest {

    private fun keywordOf(body: String) = OptOutKeywordDetector.detect(body)?.keyword

    @Test
    fun `finds keyword in the standard reply-to-opt-out phrasing`() {
        assertEquals("STOP", keywordOf("FLASH SALE 50% off! Reply STOP to opt out"))
        assertEquals("STOP", keywordOf("Msg&data rates may apply. Reply STOP to unsubscribe."))
        assertEquals("END", keywordOf("Your trial ends soon. Text END to cancel."))
        assertEquals("QUIT", keywordOf("Txt QUIT to stop receiving these alerts"))
    }

    @Test
    fun `finds keywords other than STOP`() {
        assertEquals("UNSUB", keywordOf("Deals daily! Reply UNSUB to be removed"))
        assertEquals("CANCEL", keywordOf("Reply CANCEL to end messages"))
        assertEquals("STOPALL", keywordOf("Reply STOPALL to opt out of all messages"))
        assertEquals("ARRET", keywordOf("Repondez ARRET pour stop"))
    }

    @Test
    fun `finds keyword when the instruction omits the reply verb`() {
        assertEquals("STOP", keywordOf("Limited offer inside. STOP to opt out."))
        assertEquals("END", keywordOf("Alerts from ACME. END to cancel."))
    }

    @Test
    fun `falls back to a bare shouted keyword`() {
        val detected = OptOutKeywordDetector.detect("ACME ALERTS: acct low. Msg&data rates. STOP")
        assertEquals("STOP", detected?.keyword)
        assertEquals(KeywordConfidence.LIKELY, detected?.confidence)
    }

    @Test
    fun `reports explicit confidence only for spelled-out instructions`() {
        assertEquals(
            KeywordConfidence.EXPLICIT,
            OptOutKeywordDetector.detect("Reply STOP to unsubscribe")?.confidence,
        )
    }

    @Test
    fun `returns null when there is no opt-out language at all`() {
        assertNull(OptOutKeywordDetector.detect("Your package is delayed, click bit.ly/x to reschedule"))
        assertNull(OptOutKeywordDetector.detect("Hi, is this still your number?"))
        assertNull(OptOutKeywordDetector.detect(""))
    }

    @Test
    fun `does not mistake ordinary lowercase words for keywords`() {
        assertNull(OptOutKeywordDetector.detect("Please reply now to cancel your appointment"))
        assertNull(OptOutKeywordDetector.detect("Text me to confirm"))
    }

    @Test
    fun `falls back to the configured keyword when nothing is detected`() {
        val detected = OptOutKeywordDetector.detectOrFallback("no instructions here", "stop")
        assertEquals("STOP", detected.keyword)
        assertEquals(KeywordConfidence.ASSUMED, detected.confidence)
    }
}
