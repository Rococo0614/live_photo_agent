package com.vivo.lpa

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class AndroidVideoFrameTime(
    val frameIndex: Int,
    val presentationTimeUs: Long
) {
    init {
        require(frameIndex >= 0)
        require(presentationTimeUs >= 0)
    }
}

data class AndroidDecodedVideoTimeline(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val containerDurationUs: Long,
    val nominalFrameRate: Double?,
    val frames: List<AndroidVideoFrameTime>
) {
    init {
        require(mimeType.startsWith("video/"))
        require(width > 0 && height > 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(containerDurationUs >= 0)
        require(nominalFrameRate == null || nominalFrameRate > 0.0)
        require(frames.isNotEmpty())
        require(frames.map { it.frameIndex } == frames.indices.toList()) {
            "decoded frame indices must be contiguous"
        }
        require(frames.zipWithNext().all { (left, right) ->
            left.presentationTimeUs <= right.presentationTimeUs
        }) { "decoded presentation timestamps must be monotonic" }
    }

    val frameCount: Int get() = frames.size
    val firstPresentationTimeUs: Long get() = frames.first().presentationTimeUs
    val lastPresentationTimeUs: Long get() = frames.last().presentationTimeUs

    fun nearestFrameIndex(timestampUs: Long): Int {
        require(timestampUs >= 0)
        var low = 0
        var high = frames.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (frames[middle].presentationTimeUs < timestampUs) low = middle + 1 else high = middle
        }
        val right = low
        val left = (right - 1).coerceAtLeast(0)
        val leftDistance = abs(frames[left].presentationTimeUs - timestampUs)
        val rightDistance = abs(frames[right].presentationTimeUs - timestampUs)
        return if (leftDistance <= rightDistance) left else right
    }

    fun medianPositiveFrameIntervalUs(): Long? {
        val intervals = frames.zipWithNext().mapNotNull { (left, right) ->
            (right.presentationTimeUs - left.presentationTimeUs).takeIf { it > 0 }
        }.sorted()
        if (intervals.isEmpty()) return null
        return intervals[intervals.size / 2]
    }
}

/** Decodes until MediaCodec EOS and records the actual output PTS timeline. */
object AndroidVideoFrameTimelineDecoder {
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    fun decode(
        video: File,
        cancelled: AtomicBoolean,
        timeoutSeconds: Long = 120
    ): AndroidDecodedVideoTimeline {
        require(video.isFile)
        require(timeoutSeconds in 1..600)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(video.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("video track is missing")
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(sourceFormat.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(trackIndex)
            sourceFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            var outputFormat = sourceFormat
            var inputEnded = false
            var outputEnded = false
            var lastQueuedTimeUs = 0L
            val frames = mutableListOf<AndroidVideoFrameTime>()
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!outputEnded) {
                if (cancelled.get()) throw InterruptedException("video timeline decode cancelled")
                check(System.nanoTime() < deadline) {
                    "video timeline decode timed out after ${timeoutSeconds}s"
                }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
                        val sampleSize = extractor.readSampleData(input, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                lastQueuedTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                            lastQueuedTimeUs = sampleTimeUs
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!codecConfig && info.size > 0) {
                            frames += AndroidVideoFrameTime(frames.size, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (eos) outputEnded = true
                    }
                }
            }
            check(inputEnded && frames.isNotEmpty()) { "decoder reached EOS without video frames" }
            val width = croppedDimension(outputFormat, horizontal = true)
            val height = croppedDimension(outputFormat, horizontal = false)
            val rotation = sourceFormat.integerOrNull(MediaFormat.KEY_ROTATION)
                ?.let { ((it % 360) + 360) % 360 }
                ?: 0
            val durationUs = sourceFormat.longOrNull(MediaFormat.KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
            val frameRate = sourceFormat.numberOrNull(MediaFormat.KEY_FRAME_RATE)
                ?.toDouble()
                ?.takeIf { it > 0.0 }
            return AndroidDecodedVideoTimeline(
                mime,
                width,
                height,
                rotation,
                durationUs,
                frameRate,
                frames
            )
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun croppedDimension(format: MediaFormat, horizontal: Boolean): Int {
        val baseKey = if (horizontal) MediaFormat.KEY_WIDTH else MediaFormat.KEY_HEIGHT
        val startKey = if (horizontal) "crop-left" else "crop-top"
        val endKey = if (horizontal) "crop-right" else "crop-bottom"
        val base = format.getInteger(baseKey)
        val start = format.integerOrNull(startKey)
        val end = format.integerOrNull(endKey)
        return if (start != null && end != null && end >= start) end - start + 1 else base
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private fun MediaFormat.numberOrNull(key: String): Number? =
        integerOrNull(key) ?: if (containsKey(key)) runCatching { getFloat(key) }.getOrNull() else null
}

data class AndroidDecodedBitmapFrame(
    val frameIndex: Int,
    val presentationTimeUs: Long,
    val bitmap: Bitmap
)

/** Bounded bitmap reader keyed by the already-decoded PTS timeline. */
class AndroidVideoFrameBatchReader(
    video: File,
    private val timeline: AndroidDecodedVideoTimeline
) : Closeable {
    private val retriever = MediaMetadataRetriever().apply {
        setDataSource(video.absolutePath)
    }

    fun read(startFrameIndex: Int, count: Int): List<AndroidDecodedBitmapFrame> {
        require(startFrameIndex in 0 until timeline.frameCount)
        require(count > 0 && startFrameIndex + count <= timeline.frameCount)
        val bitmaps = runCatching {
            retriever.getFramesAtIndex(startFrameIndex, count)
        }.getOrNull()?.takeIf { it.size == count } ?: (0 until count).map { offset ->
            val frame = timeline.frames[startFrameIndex + offset]
            requireNotNull(retriever.getFrameAtTime(
                frame.presentationTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )) { "unable to decode frame ${frame.frameIndex}" }
        }
        return bitmaps.mapIndexed { offset, bitmap ->
            val frame = timeline.frames[startFrameIndex + offset]
            val expectedDimensions = setOf(
                timeline.width to timeline.height,
                timeline.height to timeline.width
            )
            require((bitmap.width to bitmap.height) in expectedDimensions) {
                "decoded bitmap dimensions ${bitmap.width}x${bitmap.height} do not match timeline"
            }
            AndroidDecodedBitmapFrame(frame.frameIndex, frame.presentationTimeUs, bitmap)
        }
    }

    override fun close() {
        retriever.release()
    }
}

/** Converts a requested timeline frame directly into the EdgeTAM float input tensor. */
class AndroidTimelineEdgeTamInputMaterializer(
    video: File,
    timeline: AndroidDecodedVideoTimeline
) : AndroidEdgeTamFrameInputMaterializer, Closeable {
    private val reader = AndroidVideoFrameBatchReader(video, timeline)

    override fun materialize(frame: AndroidVideoFrameTime, destination: File) {
        val decoded = reader.read(frame.frameIndex, 1).single()
        require(decoded.presentationTimeUs == frame.presentationTimeUs)
        try {
            AndroidOwlV2QnnLocator.writeFloats(
                destination,
                AndroidEdgeTamQnnSegmenter.preprocessImage(decoded.bitmap)
            )
        } finally {
            decoded.bitmap.recycle()
        }
    }

    override fun close() {
        reader.close()
    }
}
