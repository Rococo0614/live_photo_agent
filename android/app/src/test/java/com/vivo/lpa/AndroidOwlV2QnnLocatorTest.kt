package com.vivo.lpa

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AndroidOwlV2QnnLocatorTest {
    @Test
    fun rawFloatCodecIsLittleEndianAndRoundTrips() {
        val file = File.createTempFile("owlv2-floats", ".raw")
        try {
            val expected = floatArrayOf(0f, 1f, -2.5f, Float.MIN_VALUE, Float.MAX_VALUE)

            AndroidOwlV2QnnLocator.writeFloats(file, expected)

            assertArrayEquals(expected, AndroidOwlV2QnnLocator.readFloats(file, expected.size), 0f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rawIntCodecIsLittleEndianAndRoundTrips() {
        val file = File.createTempFile("owlv2-ints", ".raw")
        try {
            val expected = intArrayOf(0, 1, -2, Int.MIN_VALUE, Int.MAX_VALUE)

            AndroidOwlV2QnnLocator.writeInts(file, expected)

            assertArrayEquals(expected, AndroidOwlV2QnnLocator.readInts(file, expected.size))
        } finally {
            file.delete()
        }
    }
}
