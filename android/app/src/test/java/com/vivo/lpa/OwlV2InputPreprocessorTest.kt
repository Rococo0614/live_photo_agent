package com.vivo.lpa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OwlV2InputPreprocessorTest {
    @Test
    fun clipBpeMatchesOfficialOwlv2TokenIdsForValidatedQueries() {
        val tokenizer = tokenizer()

        val broad = tokenizer.encode("a cat")
        val precise = tokenizer.encode("an orange cat walking on green grass")

        assertArrayEquals(
            intArrayOf(49406, 320, 2368, 49407) + IntArray(12),
            broad.inputIds
        )
        assertArrayEquals(intArrayOf(1, 1, 1, 1) + IntArray(12), broad.attentionMask)
        assertArrayEquals(
            intArrayOf(49406, 550, 4287, 2368, 3941, 525, 1901, 5922, 49407) +
                IntArray(7),
            precise.inputIds
        )
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1) + IntArray(7),
            precise.attentionMask)
    }

    @Test
    fun firstBridgeRejectsNonAsciiAndOverlongCanonicalQueries() {
        val tokenizer = tokenizer()

        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("画面中的猫") }
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer.encode(List(15) { "cat" }.joinToString(" "))
        }
    }

    @Test
    fun imagePreprocessMatchesOfficialFastProcessorDownsampleFixture() {
        val width = 4
        val height = 5
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val red = (x * 50 + y * 11 + 3) % 256
            val green = (x * 7 + y * 40 + 9) % 256
            val blue = (x * 31 + y * 17 + 21) % 256
            0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        val expected = floatArrayOf(
            -1.4387291670f, -0.2327699214f, 0.2137501240f,
            -1.1734181643f, 0.0325409770f, 0.3050703108f,
            -0.9081072211f, 0.2978517711f, 0.3963907361f,
            -1.3716847897f, -1.1981163025f, -0.2707720101f,
            -0.3798651993f, -0.2062967420f, 0.0706132427f,
            0.6119539142f, 0.7855225801f, 0.4119985998f,
            -0.9441869855f, -0.2158710212f, 0.2941067815f,
            -0.5447879434f, 0.1835280657f, 0.4315802753f,
            -0.1453888416f, 0.5829270482f, 0.5690538883f
        )

        val actual = OwlV2ImagePreprocessor.preprocessArgb(
            width, height, pixels, targetSize = 3
        )

        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("pixel $index", expected[index], actual[index], 2e-5f)
        }
    }

    private fun tokenizer(): OwlV2ClipTokenizer {
        val vocabulary = mapOf(
            "a</w>" to 320,
            "cat</w>" to 2368,
            "an</w>" to 550,
            "orange</w>" to 4287,
            "walking</w>" to 3941,
            "on</w>" to 525,
            "green</w>" to 1901,
            "grass</w>" to 5922
        )
        val merges = listOf(
            "i" to "n",
            "a" to "n",
            "r" to "e",
            "in" to "g</w>",
            "o" to "r",
            "o" to "n</w>",
            "a" to "l",
            "a" to "t</w>",
            "a" to "n</w>",
            "r" to "a",
            "e" to "n</w>",
            "s" to "s</w>",
            "k" to "ing</w>",
            "g" to "re",
            "g" to "e</w>",
            "g" to "ra",
            "w" to "al",
            "gre" to "en</w>",
            "c" to "at</w>",
            "an" to "ge</w>",
            "wal" to "king</w>",
            "or" to "ange</w>",
            "gra" to "ss</w>"
        )
        return OwlV2ClipTokenizer.fromTables(vocabulary, merges)
    }
}
