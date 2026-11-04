package com.vivo.lpa

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHttpMaskedGenerationBackendTest {
    @Test
    fun endpointPolicyAllowsTlsOrLoopbackOnly() {
        assertTrue(AndroidHttpGenerationConfig.endpointAllowed("https://generator.example/v1/inpaint"))
        assertTrue(AndroidHttpGenerationConfig.endpointAllowed("http://127.0.0.1:8765/v1/inpaint"))
        assertTrue(AndroidHttpGenerationConfig.endpointAllowed("http://localhost:8765/v1/inpaint"))
        assertFalse(AndroidHttpGenerationConfig.endpointAllowed("http://generator.example/inpaint"))
        assertFalse(AndroidHttpGenerationConfig.endpointAllowed("file:///tmp/inpaint"))
    }

    @Test
    fun responseIsRecompositedOnlyInsideCertifiedMask() {
        val source = intArrayOf(
            0xff102030.toInt(), 0xff405060.toInt(), 0xff708090.toInt()
        )
        val generated = intArrayOf(
            0xffffffff.toInt(), 0xffabcdef.toInt(), 0xff010203.toInt()
        )
        val output = AndroidHttpMaskedGenerationBackend.compositeInsideMask(
            source,
            generated,
            byteArrayOf(0, 0xff.toByte(), 0x80.toByte())
        )

        assertEquals(source[0], output[0])
        assertEquals(generated[1], output[1])
        assertNotEquals(source[2], output[2])
        assertNotEquals(generated[2], output[2])
        assertEquals(source[2] ushr 24, output[2] ushr 24)
    }

    @Test
    fun privateConfigRejectsUnknownFieldsAndCleartextRemoteEndpoint() {
        val directory = Files.createTempDirectory("http-generation-config").toFile()
        val valid = directory.resolve("valid.json").apply {
            writeText("""{"endpoint":"http://127.0.0.1:8765/v1/inpaint","model_id":"test"}""")
        }
        val remote = directory.resolve("remote.json").apply {
            writeText("""{"endpoint":"http://example.com/inpaint","model_id":"test"}""")
        }
        val unknown = directory.resolve("unknown.json").apply {
            writeText("""{"endpoint":"https://example.com/inpaint","model_id":"test","extra":1}""")
        }

        assertEquals("test", AndroidHttpGenerationConfig.fromFile(valid)?.modelId)
        assertNull(AndroidHttpGenerationConfig.fromFile(remote))
        assertNull(AndroidHttpGenerationConfig.fromFile(unknown))
        directory.deleteRecursively()
    }
}
