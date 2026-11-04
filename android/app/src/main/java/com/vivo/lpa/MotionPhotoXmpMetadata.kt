package com.vivo.lpa

import java.io.File
import java.io.RandomAccessFile

/** Minimal fallback reader for Motion Photo timing when the vendor SDK cannot decode the JPEG. */
internal object MotionPhotoXmpMetadata {
    private const val MAX_HEADER_BYTES = 1024 * 1024

    fun presentationTimestampUs(file: File): Long? {
        if (!file.isFile || file.length() <= 0L) return null
        val count = minOf(file.length(), MAX_HEADER_BYTES.toLong()).toInt()
        val header = ByteArray(count)
        RandomAccessFile(file, "r").use { input -> input.readFully(header) }
        return presentationTimestampUs(header.toString(Charsets.ISO_8859_1))
    }

    fun presentationTimestampUs(xmpText: String): Long? {
        val value = ATTRIBUTE.find(xmpText)?.groupValues?.get(1)
            ?: ELEMENT.find(xmpText)?.groupValues?.get(1)
            ?: return null
        return value.toLongOrNull()?.takeIf { it >= 0L }
    }

    private val ATTRIBUTE = Regex(
        """(?i)[A-Za-z][\w-]*:(?:MotionPhoto|MicroVideo)PresentationTimestampUs\s*=\s*["'](\d+)["']"""
    )
    private val ELEMENT = Regex(
        """(?is)<[A-Za-z][\w-]*:(?:MotionPhoto|MicroVideo)PresentationTimestampUs>\s*(\d+)\s*</[A-Za-z][\w-]*:(?:MotionPhoto|MicroVideo)PresentationTimestampUs>"""
    )
}
