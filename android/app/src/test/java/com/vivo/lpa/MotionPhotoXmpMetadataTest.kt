package com.vivo.lpa

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPhotoXmpMetadataTest {
    @Test
    fun readsVivoGoogleMotionPhotoAttribute() {
        assertEquals(
            1_500_000L,
            MotionPhotoXmpMetadata.presentationTimestampUs(
                "GCamera:MotionPhotoPresentationTimestampUs=\"1500000\""
            )
        )
    }

    @Test
    fun readsElementAndLegacyMicroVideoForms() {
        assertEquals(
            66000L,
            MotionPhotoXmpMetadata.presentationTimestampUs(
                "<Camera:MicroVideoPresentationTimestampUs>66000" +
                    "</Camera:MicroVideoPresentationTimestampUs>"
            )
        )
    }

    @Test
    fun rejectsMissingOrNegativeTimestamp() {
        assertNull(MotionPhotoXmpMetadata.presentationTimestampUs("MotionPhoto=1"))
        assertNull(MotionPhotoXmpMetadata.presentationTimestampUs(
            "GCamera:MotionPhotoPresentationTimestampUs=\"-1\""
        ))
    }

    @Test
    fun readsTimestampFromJpegHeaderBytes() {
        val file = Files.createTempFile("motion-photo", ".jpg").toFile()
        file.writeBytes(
            byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
                """<x:xmpmeta GCamera:MotionPhotoPresentationTimestampUs="240000"/>"""
                    .toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0xff.toByte(), 0xd9.toByte())
        )
        assertEquals(240000L, MotionPhotoXmpMetadata.presentationTimestampUs(file))
        file.delete()
    }
}
