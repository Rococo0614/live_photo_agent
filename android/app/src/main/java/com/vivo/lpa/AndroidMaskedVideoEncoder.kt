package com.vivo.lpa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class AndroidMaskedVideoEncodeResult(
    val output: File,
    val videoFrameCount: Int,
    val audioSampleCount: Int,
    val encoderName: String,
    val wallElapsedMs: Long
)

/** Persistent masked GLES effect -> MediaCodec AVC, followed by bitstream-level audio copy. */
class AndroidMaskedVideoEncoder {
    fun encodeTint(
        sourceVideo: File,
        timeline: AndroidDecodedVideoTimeline,
        maskFiles: List<File>,
        output: File,
        colorHex: String,
        intensity: Double,
        cancelled: AtomicBoolean
    ): AndroidMaskedVideoEncodeResult = encode(
        sourceVideo,
        timeline,
        maskFiles,
        output,
        cancelled
    ) { width, height, surface ->
        AndroidPersistentTintRenderer.forEncoder(
            width,
            height,
            colorHex,
            intensity,
            surface
        )
    }

    fun encodeBlur(
        sourceVideo: File,
        timeline: AndroidDecodedVideoTimeline,
        maskFiles: List<File>,
        output: File,
        radiusPx: Double,
        cancelled: AtomicBoolean
    ): AndroidMaskedVideoEncodeResult = encode(
        sourceVideo,
        timeline,
        maskFiles,
        output,
        cancelled
    ) { width, height, surface ->
        AndroidPersistentTintRenderer.forEncoderBlur(width, height, radiusPx, surface)
    }

    private fun encode(
        sourceVideo: File,
        timeline: AndroidDecodedVideoTimeline,
        maskFiles: List<File>,
        output: File,
        cancelled: AtomicBoolean,
        rendererFactory: (Int, Int, android.view.Surface) -> AndroidPersistentTintRenderer
    ): AndroidMaskedVideoEncodeResult {
        require(sourceVideo.isFile)
        require(maskFiles.size == timeline.frameCount && maskFiles.all(File::isFile))
        val width = if (timeline.rotationDegrees in setOf(90, 270)) timeline.height else timeline.width
        val height = if (timeline.rotationDegrees in setOf(90, 270)) timeline.width else timeline.height
        output.parentFile?.mkdirs()
        output.delete()
        val sourceTracks = sourceTrackFormats(sourceVideo)
        val frameRate = timeline.nominalFrameRate?.roundToInt()?.coerceAtLeast(1)
            ?: timeline.medianPositiveFrameIntervalUs()
                ?.let { (1_000_000.0 / it).roundToInt().coerceAtLeast(1) }
            ?: 30
        val bitrate = sourceTracks.video.integerOrNull(MediaFormat.KEY_BIT_RATE)
            ?.coerceAtLeast(1_000_000)
            ?: (width * height * 4).coerceAtLeast(2_000_000)
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            .apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var inputSurface: android.view.Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrack = -1
        var audioTrack = -1
        var encodedFrames = 0
        var audioSamples = 0
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()

            fun startMuxer(format: MediaFormat) {
                if (muxerStarted) return
                videoTrack = muxer.addTrack(format)
                sourceTracks.audio?.let { audioTrack = muxer.addTrack(it) }
                muxer.start()
                muxerStarted = true
            }

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(encoder.outputFormat)
                        index >= 0 -> {
                            val buffer = requireNotNull(encoder.getOutputBuffer(index))
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(muxerStarted && videoTrack >= 0)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, buffer, info)
                                encodedFrames += 1
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (eos) return
                        }
                    }
                }
            }

            AndroidVideoFrameBatchReader(sourceVideo, timeline).use { reader ->
                rendererFactory(width, height, requireNotNull(inputSurface)).use { renderer ->
                    timeline.frames.forEach { frame ->
                        if (cancelled.get()) throw InterruptedException("masked video encode cancelled")
                        val bitmap = reader.read(frame.frameIndex, 1).single().bitmap
                        try {
                            renderer.renderToEncoder(
                                bitmap,
                                maskFiles[frame.frameIndex].readBytes(),
                                frame.presentationTimeUs
                            )
                        } finally {
                            bitmap.recycle()
                        }
                        drain(endOfStream = false)
                    }
                    check(renderer.renderedFrameCount == timeline.frameCount)
                }
            }
            encoder.signalEndOfInputStream()
            drain(endOfStream = true)
            check(encodedFrames == timeline.frameCount) {
                "encoder produced $encodedFrames/${timeline.frameCount} frames"
            }
            if (audioTrack >= 0) {
                audioSamples = copyAudioSamples(sourceVideo, muxer, audioTrack, cancelled)
            }
            check(muxerStarted)
            muxer.stop()
            muxerStarted = false
            check(output.isFile && output.length() > 0)
            return AndroidMaskedVideoEncodeResult(
                output,
                encodedFrames,
                audioSamples,
                encoder.name,
                android.os.SystemClock.elapsedRealtime() - startedAt
            )
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
            runCatching { encoder.stop() }
            encoder.release()
            inputSurface?.release()
        }
    }

    private data class SourceTrackFormats(
        val video: MediaFormat,
        val audio: MediaFormat?
    )

    private fun sourceTrackFormats(source: File): SourceTrackFormats {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                when {
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true -> video = format
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true -> audio = format
                }
            }
            SourceTrackFormats(requireNotNull(video), audio)
        } finally {
            extractor.release()
        }
    }

    private fun copyAudioSamples(
        source: File,
        muxer: MediaMuxer,
        destinationTrack: Int,
        cancelled: AtomicBoolean
    ): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return 0
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocateDirect(
                format.integerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(64 * 1024)
                    ?: 1024 * 1024
            )
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                if (cancelled.get()) throw InterruptedException("audio copy cancelled")
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val extractorFlags = extractor.sampleFlags
                require(extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                    "encrypted audio samples cannot be copied into a private preview"
                }
                var codecFlags = 0
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                info.set(0, size, extractor.sampleTime, codecFlags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(destinationTrack, buffer, info)
                samples += 1
                extractor.advance()
            }
            samples
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}

data class AndroidMediaTrackFingerprint(
    val mimeType: String,
    val sampleCount: Int,
    val totalBytes: Long,
    val sampleDataSha256: String,
    val timelineSha256: String
)

object AndroidMediaTrackInspector {
    fun fingerprint(file: File, mimePrefix: String): AndroidMediaTrackFingerprint? {
        require(file.isFile && mimePrefix.endsWith("/"))
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith(mimePrefix) == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(track)
            val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrNull()
            } else null
            val buffer = ByteBuffer.allocateDirect(capacity?.coerceAtLeast(64 * 1024) ?: 1024 * 1024)
            val dataDigest = MessageDigest.getInstance("SHA-256")
            val timelineDigest = MessageDigest.getInstance("SHA-256")
            var count = 0
            var total = 0L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes)
                dataDigest.update(bytes)
                timelineDigest.update(ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                    .putLong(extractor.sampleTime)
                    .putInt(extractor.sampleFlags)
                    .putInt(size)
                    .array())
                count += 1
                total += size
                extractor.advance()
            }
            fun hex(bytes: ByteArray) = bytes.joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            AndroidMediaTrackFingerprint(
                mime,
                count,
                total,
                hex(dataDigest.digest()),
                hex(timelineDigest.digest())
            )
        } finally {
            extractor.release()
        }
    }
}
