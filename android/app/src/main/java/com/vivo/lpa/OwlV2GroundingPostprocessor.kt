package com.vivo.lpa

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class OwlV2InternalBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    init {
        require(listOf(left, top, right, bottom).all(Double::isFinite))
        require(right > left && bottom > top)
    }

    val area: Double get() = (right - left) * (bottom - top)
}

/** Internal observation only. It is never accepted as user input or emitted in Goal IR. */
data class OwlV2InternalCandidate(
    val candidateId: String,
    val rawIndex: Int,
    val label: Int,
    val score: Float,
    val box: OwlV2InternalBox
)

enum class OwlV2GroundingDisposition {
    NEED_OBSERVATION,
    NEED_USER,
    CANDIDATE_SET_READY
}

data class OwlV2GroundingResult(
    val canonicalQuery: String,
    val sourceText: String?,
    val expectedCount: Int,
    val candidates: List<OwlV2InternalCandidate>,
    val disposition: OwlV2GroundingDisposition,
    val evidenceFingerprint: String
) {
    val contentMutationAllowed: Boolean = false

    fun toShadowObservation(
        operationId: String,
        sourceAssetId: String,
        observedAtMs: Long
    ): ShadowTargetGroundingObservation = ShadowTargetGroundingObservation(
        operationId = operationId,
        semanticTarget = canonicalQuery,
        sourceAssetId = sourceAssetId,
        candidateCount = candidates.size,
        observedAtMs = observedAtMs,
        locatorEvidenceFingerprint = evidenceFingerprint
    )
}

/** Deterministic host NMS, cardinality decision and evidence hashing for OWLv2 outputs. */
object OwlV2GroundingPostprocessor {
    const val MODEL_IMAGE_SIZE = 960

    fun process(
        canonicalQuery: String,
        sourceText: String?,
        stateFingerprint: String,
        imageWidth: Int,
        imageHeight: Int,
        boxesXyxy: FloatArray,
        scores: FloatArray,
        labels: IntArray,
        expectedCount: Int = 1,
        scoreThreshold: Double = 0.10,
        iouThreshold: Double = 0.50
    ): OwlV2GroundingResult {
        require(canonicalQuery.isNotBlank())
        require(stateFingerprint.isNotBlank())
        require(imageWidth > 0 && imageHeight > 0)
        require(expectedCount > 0)
        require(scoreThreshold in 0.0..1.0 && iouThreshold in 0.0..1.0)
        require(boxesXyxy.size == scores.size * 4)
        require(labels.size == scores.size)

        val eligible = scores.indices.mapNotNull { index ->
            val score = scores[index]
            if (!score.isFinite() || score < scoreThreshold) return@mapNotNull null
            val offset = index * 4
            val raw = floatArrayOf(
                boxesXyxy[offset],
                boxesXyxy[offset + 1],
                boxesXyxy[offset + 2],
                boxesXyxy[offset + 3]
            )
            if (raw.any { !it.isFinite() } || raw[2] <= raw[0] || raw[3] <= raw[1]) {
                return@mapNotNull null
            }
            RawCandidate(index, labels[index], score, raw)
        }.sortedWith(compareByDescending<RawCandidate> { it.score }.thenBy { it.index })

        val retained = mutableListOf<RawCandidate>()
        eligible.forEach { candidate ->
            val suppressed = retained.any { accepted ->
                accepted.label == candidate.label && iou(accepted.box, candidate.box) > iouThreshold
            }
            if (!suppressed) retained += candidate
        }

        val scale = MODEL_IMAGE_SIZE.toDouble() / max(imageWidth, imageHeight)
        val contextCandidates = retained.mapNotNull { candidate ->
            val left = (candidate.box[0] / scale).coerceIn(0.0, imageWidth.toDouble())
            val top = (candidate.box[1] / scale).coerceIn(0.0, imageHeight.toDouble())
            val right = (candidate.box[2] / scale).coerceIn(0.0, imageWidth.toDouble())
            val bottom = (candidate.box[3] / scale).coerceIn(0.0, imageHeight.toDouble())
            if (right <= left || bottom <= top) return@mapNotNull null
            OwlV2InternalCandidate(
                candidateId = "candidate-${candidate.index}",
                rawIndex = candidate.index,
                label = candidate.label,
                score = candidate.score,
                box = OwlV2InternalBox(left, top, right, bottom)
            )
        }.mapIndexed { index, candidate -> candidate.copy(candidateId = "candidate-$index") }

        val candidates = if (expectedCount == 1 && contextCandidates.size > 1 &&
            confidentlyDominant(contextCandidates[0], contextCandidates[1])
        ) {
            listOf(contextCandidates.first())
        } else {
            contextCandidates
        }
        val disposition = when {
            candidates.isEmpty() -> OwlV2GroundingDisposition.NEED_OBSERVATION
            candidates.size != expectedCount -> OwlV2GroundingDisposition.NEED_USER
            else -> OwlV2GroundingDisposition.CANDIDATE_SET_READY
        }
        return OwlV2GroundingResult(
            canonicalQuery = canonicalQuery,
            sourceText = sourceText,
            expectedCount = expectedCount,
            candidates = candidates,
            disposition = disposition,
            evidenceFingerprint = fingerprint(
                canonicalQuery,
                sourceText,
                stateFingerprint,
                imageWidth,
                imageHeight,
                expectedCount,
                scoreThreshold,
                iouThreshold,
                contextCandidates
            )
        )
    }

    private fun confidentlyDominant(
        first: OwlV2InternalCandidate,
        second: OwlV2InternalCandidate
    ): Boolean = first.score >= DOMINANT_MIN_SCORE &&
        first.score - second.score >= DOMINANT_MIN_MARGIN &&
        first.score / second.score.coerceAtLeast(0.0001f) >= DOMINANT_MIN_RATIO

    private data class RawCandidate(
        val index: Int,
        val label: Int,
        val score: Float,
        val box: FloatArray
    )

    private fun iou(left: FloatArray, right: FloatArray): Double {
        val intersectionWidth = max(0f, min(left[2], right[2]) - max(left[0], right[0]))
        val intersectionHeight = max(0f, min(left[3], right[3]) - max(left[1], right[1]))
        val intersection = intersectionWidth.toDouble() * intersectionHeight
        if (intersection <= 0.0) return 0.0
        val leftArea = (left[2] - left[0]).toDouble() * (left[3] - left[1])
        val rightArea = (right[2] - right[0]).toDouble() * (right[3] - right[1])
        return intersection / (leftArea + rightArea - intersection)
    }

    private fun fingerprint(
        canonicalQuery: String,
        sourceText: String?,
        stateFingerprint: String,
        imageWidth: Int,
        imageHeight: Int,
        expectedCount: Int,
        scoreThreshold: Double,
        iouThreshold: Double,
        candidates: List<OwlV2InternalCandidate>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun text(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(intBytes(bytes.size))
            digest.update(bytes)
        }
        fun integer(value: Int) = digest.update(intBytes(value))
        fun long(value: Long) = digest.update(longBytes(value))

        text(canonicalQuery)
        text(sourceText.orEmpty())
        text(stateFingerprint)
        integer(imageWidth)
        integer(imageHeight)
        integer(expectedCount)
        long(scoreThreshold.toBits())
        long(iouThreshold.toBits())
        integer(candidates.size)
        candidates.forEach { candidate ->
            integer(candidate.rawIndex)
            integer(candidate.label)
            integer(candidate.score.toRawBits())
            long(candidate.box.left.toBits())
            long(candidate.box.top.toBits())
            long(candidate.box.right.toBits())
            long(candidate.box.bottom.toBits())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value)
        .array()

    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value)
        .array()

    private const val DOMINANT_MIN_SCORE = 0.20f
    private const val DOMINANT_MIN_MARGIN = 0.08f
    private const val DOMINANT_MIN_RATIO = 1.40f
}
