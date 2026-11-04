package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
