package com.vivo.lpa

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class AndroidMaskBounds(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int
) {
    init {
        require(left >= 0 && top >= 0 && rightExclusive > left && bottomExclusive > top)
    }
    val width: Int get() = rightExclusive - left
    val height: Int get() = bottomExclusive - top
    val area: Long get() = width.toLong() * height
}

object AndroidGenerationRegionPolicy {
    const val DEFAULT_PADDING_PX = 24
    const val MIN_MASK_AREA_RATIO = 0.001
    private const val MIN_BOUNDS_AREA_RATIO = 0.45
    private const val MAX_BOUNDS_AREA_RATIO = 2.20
    private const val MAX_LOG_ASPECT_DELTA = 0.55

    fun bounds(mask: ByteArray, width: Int, height: Int): AndroidMaskBounds? {
        require(width > 0 && height > 0 && mask.size == width * height)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        mask.forEachIndexed { index, value ->
            if ((value.toInt() and 0xff) == 0) return@forEachIndexed
            val x = index % width
            val y = index / width
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return if (right < left || bottom < top) null else AndroidMaskBounds(
            left, top, right + 1, bottom + 1
        )
    }

    fun retainLargestComponent(mask: ByteArray, width: Int, height: Int): ByteArray {
        return retainComponents(
            mask,
            width,
            height,
            minimumRelativeArea = 1.0,
            minimumAbsoluteArea = 1
        )
    }

    /** Keeps real disconnected object parts while rejecting tiny segmentation speckles. */
    fun retainSignificantComponents(
        mask: ByteArray,
        width: Int,
        height: Int,
        minimumRelativeArea: Double = 0.01
    ): ByteArray = retainComponents(
        mask,
        width,
        height,
        minimumRelativeArea,
        minimumAbsoluteArea = 4
    )

    private fun retainComponents(
        mask: ByteArray,
        width: Int,
        height: Int,
        minimumRelativeArea: Double,
        minimumAbsoluteArea: Int
    ): ByteArray {
        require(width > 0 && height > 0 && mask.size == width * height)
        require(minimumRelativeArea in 0.0..1.0)
        require(minimumAbsoluteArea > 0)
        val labels = IntArray(mask.size)
        val queue = IntArray(mask.size)
        val counts = IntArray(mask.size + 1)
        var label = 0
        var bestCount = 0
        for (start in mask.indices) {
            if ((mask[start].toInt() and 0xff) == 0 || labels[start] != 0) continue
            label += 1
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var count = 0
            while (head < tail) {
                val index = queue[head++]
                count += 1
                val x = index % width
                val y = index / width
                fun add(next: Int) {
                    if (labels[next] == 0 && (mask[next].toInt() and 0xff) > 0) {
                        labels[next] = label
                        queue[tail++] = next
                    }
                }
                if (x > 0) add(index - 1)
                if (x + 1 < width) add(index + 1)
                if (y > 0) add(index - width)
                if (y + 1 < height) add(index + width)
            }
            counts[label] = count
            if (count > bestCount) {
                bestCount = count
            }
        }
        require(bestCount > 0) { "generation mask has no connected component" }
        val minimumCount = maxOf(
            minimumAbsoluteArea,
            ceil(bestCount * minimumRelativeArea).toInt()
        )
        return ByteArray(mask.size) { index ->
            if (counts[labels[index]] >= minimumCount) 0xff.toByte() else 0
        }
    }

    fun dilateBinaryMask(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        require(width > 0 && height > 0 && mask.size == width * height)
        require(radius in 0..32)
        if (radius == 0) return mask.copyOf()
        val horizontal = ByteArray(mask.size)
        val prefix = IntArray(maxOf(width, height) + 1)
        for (y in 0 until height) {
            prefix[0] = 0
            val row = y * width
            for (x in 0 until width) {
                prefix[x + 1] = prefix[x] +
                    if ((mask[row + x].toInt() and 0xff) > 0) 1 else 0
            }
            for (x in 0 until width) {
                val start = (x - radius).coerceAtLeast(0)
                val end = (x + radius + 1).coerceAtMost(width)
                if (prefix[end] > prefix[start]) horizontal[row + x] = 0xff.toByte()
            }
        }
        val output = ByteArray(mask.size)
        for (x in 0 until width) {
            prefix[0] = 0
            for (y in 0 until height) {
                prefix[y + 1] = prefix[y] +
                    if ((horizontal[y * width + x].toInt() and 0xff) > 0) 1 else 0
            }
            for (y in 0 until height) {
                val start = (y - radius).coerceAtLeast(0)
                val end = (y + radius + 1).coerceAtMost(height)
                if (prefix[end] > prefix[start]) output[y * width + x] = 0xff.toByte()
            }
        }
        return output
    }

    fun rectangularEnvelope(
        mask: ByteArray,
        width: Int,
        height: Int,
        maximumFrameAreaRatio: Double = 0.08,
        maximumMaskAreaMultiple: Double = 3.0
    ): ByteArray {
        require(width > 0 && height > 0 && mask.size == width * height)
        require(maximumFrameAreaRatio in 0.0..1.0)
        require(maximumMaskAreaMultiple >= 1.0)
        val bounds = requireNotNull(bounds(mask, width, height)) {
            "generation mask is empty"
        }
        val selected = mask.count { (it.toInt() and 0xff) > 0 }
        val frameArea = width.toLong() * height
        require(bounds.area.toDouble() / frameArea <= maximumFrameAreaRatio) {
            "generation propagation envelope is too large for the frame"
        }
        require(bounds.area.toDouble() / selected <= maximumMaskAreaMultiple) {
            "generation propagation envelope expands the certified mask too far"
        }
        return ByteArray(mask.size).also { output ->
            for (y in bounds.top until bounds.bottomExclusive) {
                output.fill(
                    0xff.toByte(),
                    y * width + bounds.left,
                    y * width + bounds.rightExclusive
                )
            }
        }
    }

    fun featherInsideMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        radius: Int
    ): ByteArray {
        require(width > 0 && height > 0 && mask.size == width * height)
        require(radius in 0..16)
        if (radius == 0) return mask.copyOf()
        val active = ByteArray(mask.size) { index ->
            if ((mask[index].toInt() and 0xff) > 0) 1 else 0
        }
        val output = ByteArray(mask.size) { index ->
            if (active[index].toInt() != 0) 0xff.toByte() else 0
        }
        val remove = IntArray(mask.size)
        for (layer in 1..radius) {
            var removeCount = 0
            for (index in active.indices) {
                if (active[index].toInt() == 0) continue
                val x = index % width
                val y = index / width
                var boundary = x == 0 || y == 0 || x + 1 == width || y + 1 == height
                if (!boundary) {
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if ((dx != 0 || dy != 0) &&
                                active[(y + dy) * width + x + dx].toInt() == 0) {
                                boundary = true
                            }
                        }
                    }
                }
                if (boundary) remove[removeCount++] = index
            }
            val alpha = (255 * layer / (radius + 1)).toByte()
            for (removeIndex in 0 until removeCount) {
                val index = remove[removeIndex]
                active[index] = 0
                output[index] = alpha
            }
            if (removeCount == 0) break
        }
        return output
    }

    fun requireRigidTrajectory(bounds: List<AndroidMaskBounds>, anchorIndex: Int) {
        require(anchorIndex in bounds.indices && bounds.isNotEmpty())
        val anchor = bounds[anchorIndex]
        val anchorAspect = anchor.width.toDouble() / anchor.height
        bounds.forEachIndexed { index, value ->
            val areaRatio = value.area.toDouble() / anchor.area
            require(areaRatio in MIN_BOUNDS_AREA_RATIO..MAX_BOUNDS_AREA_RATIO) {
                "generation mask frame $index area ratio $areaRatio is not rigid"
            }
            val aspect = value.width.toDouble() / value.height
            val logDelta = abs(kotlin.math.ln(aspect / anchorAspect))
            require(logDelta <= MAX_LOG_ASPECT_DELTA) {
                "generation mask frame $index aspect delta $logDelta is not rigid"
            }
        }
    }
}

object AndroidGeneratedPatchPropagator {
    fun renderArgb(
        width: Int,
        height: Int,
        source: IntArray,
        generatedAnchor: IntArray,
        currentMask: ByteArray,
        anchorTransformBounds: AndroidMaskBounds,
        currentTransformBounds: AndroidMaskBounds,
        currentMaskBounds: AndroidMaskBounds? = null,
        anchorSource: IntArray? = null
    ): IntArray {
        require(width > 0 && height > 0)
        require(source.size == width * height && generatedAnchor.size == source.size)
        require(currentMask.size == source.size)
        require(anchorSource == null || anchorSource.size == source.size)
        val loopBounds = currentMaskBounds ?: requireNotNull(
            AndroidGenerationRegionPolicy.bounds(currentMask, width, height)
        )
        val output = source.copyOf()
        for (y in loopBounds.top until loopBounds.bottomExclusive) {
            val anchorY = if (currentTransformBounds.height == 1) {
                anchorTransformBounds.top + anchorTransformBounds.height / 2
            } else {
                (anchorTransformBounds.top +
                    (y - currentTransformBounds.top).toDouble() *
                    (anchorTransformBounds.height - 1) /
                    (currentTransformBounds.height - 1)).roundToInt()
            }.coerceIn(0, height - 1)
            for (x in loopBounds.left until loopBounds.rightExclusive) {
                val index = y * width + x
                val alpha = currentMask[index].toInt() and 0xff
                if (alpha == 0) continue
                val anchorX = if (currentTransformBounds.width == 1) {
                    anchorTransformBounds.left + anchorTransformBounds.width / 2
                } else {
                    (anchorTransformBounds.left +
                        (x - currentTransformBounds.left).toDouble() *
                        (anchorTransformBounds.width - 1) /
                        (currentTransformBounds.width - 1)).roundToInt()
                }.coerceIn(0, width - 1)
                val anchorIndex = anchorY * width + anchorX
                val replacement = generatedAnchor[anchorIndex]
                if (anchorSource != null && !visiblyChanged(anchorSource[anchorIndex], replacement)) {
                    continue
                }
                val original = source[index]
                fun blend(shift: Int): Int {
                    val left = original ushr shift and 0xff
                    val right = replacement ushr shift and 0xff
                    return (left * (255 - alpha) + right * alpha + 127) / 255
                }
                output[index] = (original and -0x1000000) or
                    (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
            }
        }
        return output
    }

    private fun visiblyChanged(source: Int, generated: Int): Boolean =
        maxOf(
            kotlin.math.abs((source ushr 16 and 0xff) - (generated ushr 16 and 0xff)),
            kotlin.math.abs((source ushr 8 and 0xff) - (generated ushr 8 and 0xff)),
            kotlin.math.abs((source and 0xff) - (generated and 0xff))
        ) > 4
}
