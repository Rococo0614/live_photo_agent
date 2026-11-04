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
        val expanded = AndroidLocalGenerationPreviewShadowExecutor.dilateBinaryMask(
            mask, 5, 5, 1
        )

        assertEquals(9, expanded.count { (it.toInt() and 0xff) > 0 })
        assertTrue((expanded[1 * 5 + 1].toInt() and 0xff) > 0)
        assertEquals(1, AndroidLocalGenerationPreviewShadowExecutor.dilateBinaryMask(
            byteArrayOf(0xff.toByte()), 1, 1, 24
        ).count { (it.toInt() and 0xff) > 0 })
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
