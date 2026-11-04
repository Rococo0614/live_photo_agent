package com.vivo.lpa

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidFixtureMaskedGenerationBackendTest {
    @Test
    fun fixtureIsDeterministicAndNeverChangesPixelsOutsideMask() {
        val source = intArrayOf(
            0xff102030.toInt(), 0xff405060.toInt(),
            0xff708090.toInt(), 0xffa0b0c0.toInt()
        )
        val mask = byteArrayOf(0, 0xff.toByte(), 0x80.toByte(), 0)

        val first = AndroidFixtureMaskedGenerationBackend.renderArgb(
            2, 2, source, mask, "blue robotic cat"
        )
        val second = AndroidFixtureMaskedGenerationBackend.renderArgb(
            2, 2, source, mask, "blue robotic cat"
        )

        assertArrayEquals(first, second)
        assertEquals(source[0], first[0])
        assertEquals(source[3], first[3])
        assertNotEquals(source[1], first[1])
        assertNotEquals(source[2], first[2])
        assertEquals(source[1] ushr 24, first[1] ushr 24)
    }

    @Test
    fun promptChangesFixtureContentAndCancellationIsHonoured() {
        val source = IntArray(4) { 0xff112233.toInt() }
        val mask = ByteArray(4) { 0xff.toByte() }
        val cat = AndroidFixtureMaskedGenerationBackend.renderArgb(2, 2, source, mask, "robot cat")
        val dog = AndroidFixtureMaskedGenerationBackend.renderArgb(2, 2, source, mask, "robot dog")

        assertNotEquals(cat.toList(), dog.toList())
        assertThrows(IllegalStateException::class.java) {
            AndroidFixtureMaskedGenerationBackend.renderArgb(
                2, 2, source, mask, "robot cat", AtomicBoolean(true)
            )
        }
    }
}
