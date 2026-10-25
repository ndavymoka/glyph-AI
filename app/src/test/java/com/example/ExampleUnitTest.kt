package com.example

import com.example.glyph.PixelMatrixRenderer
import com.example.network.AiOracleClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun sanitizeUltraShortAnswer_trimsAndLimitsWordsAndStripsThinkTags() {
        assertEquals("RTX 5070", AiOracleClient.sanitizeUltraShortAnswer("**RTX 5070**.", 2))
        assertEquals(
            "RTX 5070",
            AiOracleClient.sanitizeUltraShortAnswer("<think>Comparing GPUs...</think>\nRTX 5070", 2)
        )
        assertEquals("ДА", AiOracleClient.sanitizeUltraShortAnswer("«Да!»", 2))
        assertEquals("НЕТ", AiOracleClient.sanitizeUltraShortAnswer("Нет.", 1))
        assertEquals("42", AiOracleClient.sanitizeUltraShortAnswer("42", 2))
    }

    @Test
    fun pixelMatrixRenderer_staticVsMarqueeAndHardwareScale() {
        assertTrue(PixelMatrixRenderer.shouldDisplayStatically("ДА"))
        assertTrue(PixelMatrixRenderer.shouldDisplayStatically("НЕТ"))
        assertTrue(PixelMatrixRenderer.shouldDisplayStatically("42"))
        assertFalse(PixelMatrixRenderer.shouldDisplayStatically("RTX 5070"))

        val staticFrame = PixelMatrixRenderer.renderStaticAnswer("ДА", 255)
        assertEquals(625, staticFrame.size)
        assertTrue(staticFrame.any { it == 255 })

        val hwFrame = PixelMatrixRenderer.toHardwareMatrixArray(staticFrame)
        assertEquals(625, hwFrame.size)
        assertTrue(hwFrame.any { it == 2047 })
    }
}
