package com.vivo.lpa

import kotlin.math.abs

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
        require(width > 0 && height > 0 && mask.size == width * height)
        val labels = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var label = 0
        var bestLabel = 0
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
            if (count > bestCount) {
                bestCount = count
                bestLabel = label
            }
        }
        require(bestLabel != 0) { "generation mask has no connected component" }
        return ByteArray(mask.size) { index ->
            if (labels[index] == bestLabel) 0xff.toByte() else 0
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
        anchorBounds: AndroidMaskBounds,
        currentBounds: AndroidMaskBounds
    ): IntArray {
        require(width > 0 && height > 0)
        require(source.size == width * height && generatedAnchor.size == source.size)
        require(currentMask.size == source.size)
        val output = source.copyOf()
        for (y in currentBounds.top until currentBounds.bottomExclusive) {
            val v = if (currentBounds.height == 1) 0.5 else
                (y - currentBounds.top).toDouble() / (currentBounds.height - 1)
            val anchorY = (anchorBounds.top + v * (anchorBounds.height - 1)).toInt()
                .coerceIn(anchorBounds.top, anchorBounds.bottomExclusive - 1)
            for (x in currentBounds.left until currentBounds.rightExclusive) {
                val index = y * width + x
                val alpha = currentMask[index].toInt() and 0xff
                if (alpha == 0) continue
                val u = if (currentBounds.width == 1) 0.5 else
                    (x - currentBounds.left).toDouble() / (currentBounds.width - 1)
                val anchorX = (anchorBounds.left + u * (anchorBounds.width - 1)).toInt()
                    .coerceIn(anchorBounds.left, anchorBounds.rightExclusive - 1)
                val replacement = generatedAnchor[anchorY * width + anchorX]
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
}
