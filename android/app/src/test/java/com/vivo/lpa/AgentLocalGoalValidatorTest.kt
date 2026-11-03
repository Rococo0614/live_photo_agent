package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalGoalValidatorTest {
    @Test
    fun acceptsBoundedLocalTintWithAsciiCanonicalTarget() {
        assertTrue(AgentLocalGoalValidator.validate(goal("orange cat on grass")).isEmpty())
    }

    @Test
    fun rejectsNonAsciiCanonicalTargetForFirstAndroidLocator() {
        val diagnostics = AgentLocalGoalValidator.validate(goal("草地上的橘猫"))

        assertTrue(diagnostics.any { it.code == "canonical_target_ascii" })
    }

    @Test
    fun rejectsDestinationColorLeakingIntoPreEditTarget() {
        val diagnostics = AgentLocalGoalValidator.validate(goal("green cat"))

        assertTrue(diagnostics.any { it.code == "target_effect_leakage" })
    }

    @Test
    fun permitsDestinationColorWhenItOnlyDescribesSurroundingScene() {
        assertTrue(AgentLocalGoalValidator.validate(
            goal("orange cat walking on green grass")
        ).isEmpty())
    }

    @Test
    fun rejectsCanonicalTargetThatDropsUserActionQualifier() {
        val operation = operation(AgentGoalTarget.Semantic(
            "orange cat on green grass",
            "草地上走的橘猫"
        ))
        val diagnostics = AgentLocalGoalValidator.validate(
            AgentGoalIr(
                goalId = "goal-walking",
                userGoal = "把草地上走的橘猫调成绿色",
                stateFingerprint = "state",
                operations = listOf(operation)
            )
        )

        assertTrue(diagnostics.any { it.code == "target_qualifier_loss" })
    }

    @Test
    fun unresolvedTargetRemainsAUserQuestionRatherThanAFormatError() {
        val operation = operation(target = null)
        val goal = AgentGoalIr(
            goalId = "goal-unresolved",
            userGoal = "把那个东西调成绿色",
            stateFingerprint = "state",
            operations = listOf(operation),
            unresolvedSlots = listOf(AgentUnresolvedSlot(
                "slot-target", operation.operationId, "target"
            ))
        )

        assertEquals(emptyList<AgentGoalCodecDiagnostic>(), AgentLocalGoalValidator.validate(goal))
    }

    @Test
    fun ordinaryRuntimeGoalDoesNotEnterLocalPreviewValidation() {
        val goal = AgentGoalIr(
            goalId = "goal-retrieve",
            userGoal = "找三张猫照片",
            stateFingerprint = "state",
            operations = listOf(AgentGoalOperation(
                operationId = "retrieve-1",
                kind = AgentGoalOperationKind.RETRIEVE,
                scope = AgentGoalScope.CurrentAssets
            ))
        )

        assertTrue(!AgentLocalGoalValidator.appliesTo(goal))
    }

    private fun goal(target: String) = AgentGoalIr(
        goalId = "goal-local",
        userGoal = "把草地上的橘猫调成绿色",
        stateFingerprint = "state",
        operations = listOf(operation(AgentGoalTarget.Semantic(target, "草地上的橘猫")))
    )

    private fun operation(target: AgentGoalTarget? = AgentGoalTarget.Semantic(
        "orange cat on grass", "草地上的橘猫"
    )) = AgentGoalOperation(
        operationId = "change-1",
        kind = AgentGoalOperationKind.CHANGE_CONTENT,
        scope = AgentGoalScope.CurrentAssets,
        target = target,
        parameters = mapOf(
            "effect_kind" to AgentGoalArgument(
                AgentGoalValue.Text("color_tint"), AgentValueProvenance.MODEL_INFERRED
            ),
            "color_hex" to AgentGoalArgument(
                AgentGoalValue.Text("#008000"), AgentValueProvenance.MODEL_INFERRED
            ),
            "intensity" to AgentGoalArgument(
                AgentGoalValue.Number(0.8), AgentValueProvenance.MODEL_INFERRED
            )
        )
    )
}
