package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalGenerativeChangeTest {
    @Test
    fun parsesTypedMaskedReplacementWithQuotedPromptSource() {
        val change = LocalGenerativeChange.fromOperation(operation())

        requireNotNull(change)
        assertEquals("blue robotic cat", change.generationPrompt)
        assertEquals("蓝色机械猫", change.promptSourceText)
    }

    @Test
    fun rejectsMissingProvenanceAndUnknownParameters() {
        assertNull(LocalGenerativeChange.fromOperation(operation(promptSourceText = null)))
        assertNull(LocalGenerativeChange.fromOperation(operation(extraParameter = true)))
    }

    @Test
    fun generationMaskPaddingIsDeterministicAndClippedToFrame() {
        val mask = ByteArray(25)
        mask[2 * 5 + 2] = 0xff.toByte()
        val expanded = AndroidGenerationRegionPolicy.dilateBinaryMask(
            mask, 5, 5, 1
        )

        assertEquals(9, expanded.count { (it.toInt() and 0xff) > 0 })
        assertTrue((expanded[1 * 5 + 1].toInt() and 0xff) > 0)
        assertEquals(1, AndroidGenerationRegionPolicy.dilateBinaryMask(
            byteArrayOf(0xff.toByte()), 1, 1, 24
        ).count { (it.toInt() and 0xff) > 0 })
    }

    @Test
    fun disconnectedGenerationArtifactsAreRemovedBeforePadding() {
        val mask = ByteArray(25)
        for (y in 1..2) for (x in 1..2) mask[y * 5 + x] = 0xff.toByte()
        mask[4 * 5 + 4] = 0xff.toByte()

        val cleaned = AndroidGenerationRegionPolicy.retainLargestComponent(mask, 5, 5)

        assertEquals(4, cleaned.count { (it.toInt() and 0xff) > 0 })
        assertEquals(0, cleaned[4 * 5 + 4].toInt() and 0xff)
    }

    @Test
    fun significantDisconnectedObjectPartsAreRetained() {
        val width = 20
        val height = 10
        val mask = ByteArray(width * height)
        for (y in 1..5) for (x in 1..10) mask[y * width + x] = 0xff.toByte()
        for (y in 7..8) for (x in 15..17) mask[y * width + x] = 0xff.toByte()
        mask[9 * width + 19] = 0xff.toByte()

        val cleaned = AndroidGenerationRegionPolicy.retainSignificantComponents(
            mask,
            width,
            height
        )

        assertEquals(56, cleaned.count { (it.toInt() and 0xff) > 0 })
        assertEquals(255, cleaned[7 * width + 15].toInt() and 0xff)
        assertEquals(0, cleaned[9 * width + 19].toInt() and 0xff)
    }

    @Test
    fun boundedPropagationEnvelopeConnectsCertifiedObjectParts() {
        val width = 20
        val height = 10
        val mask = ByteArray(width * height)
        for (y in 3..5) for (x in 3..6) mask[y * width + x] = 0xff.toByte()
        for (y in 3..5) for (x in 10..13) mask[y * width + x] = 0xff.toByte()

        val envelope = AndroidGenerationRegionPolicy.rectangularEnvelope(
            mask,
            width,
            height,
            maximumFrameAreaRatio = 0.2,
            maximumMaskAreaMultiple = 2.0
        )

        assertEquals(33, envelope.count { (it.toInt() and 0xff) > 0 })
        assertEquals(255, envelope[4 * width + 8].toInt() and 0xff)
    }

    @Test
    fun generationFeatheringNeverExpandsOutsideCertifiedMask() {
        val width = 9
        val height = 9
        val mask = ByteArray(width * height)
        for (y in 1..7) for (x in 1..7) mask[y * width + x] = 0xff.toByte()

        val feathered = AndroidGenerationRegionPolicy.featherInsideMask(
            mask,
            width,
            height,
            radius = 2
        )

        assertEquals(0, feathered[0].toInt() and 0xff)
        assertEquals(85, feathered[1 * width + 1].toInt() and 0xff)
        assertEquals(170, feathered[2 * width + 2].toInt() and 0xff)
        assertEquals(255, feathered[4 * width + 4].toInt() and 0xff)
        assertEquals(0, feathered.indices.count {
            (mask[it].toInt() and 0xff) == 0 && (feathered[it].toInt() and 0xff) != 0
        })
    }

    private fun operation(
        promptSourceText: String? = "蓝色机械猫",
        extraParameter: Boolean = false
    ): AgentGoalOperation {
        val parameters = linkedMapOf(
            "effect_kind" to AgentGoalArgument(
                AgentGoalValue.Text(LocalGenerativeChange.EFFECT_KIND),
                AgentValueProvenance.MODEL_INFERRED
            ),
            "generation_prompt" to AgentGoalArgument(
                AgentGoalValue.Text("blue robotic cat"),
                AgentValueProvenance.USER_EXPLICIT,
                promptSourceText
            )
        )
        if (extraParameter) {
            parameters["seed"] = AgentGoalArgument(
                AgentGoalValue.Number(1.0), AgentValueProvenance.MODEL_INFERRED
            )
        }
        return AgentGoalOperation(
            operationId = "generate-1",
            kind = AgentGoalOperationKind.CHANGE_CONTENT,
            scope = AgentGoalScope.CurrentAssets,
            target = AgentGoalTarget.Semantic("orange cat on green grass", "草地上的橘猫"),
            parameters = parameters
        )
    }
}
