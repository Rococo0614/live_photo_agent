package com.vivo.lpa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalDeterministicChangeTest {
    @Test
    fun candidateCertificateCanCompilePreviewButCannotAuthorizeMutation() {
        val goal = changeGoal()
        val certificate = certificate()

        val compilation = LocalDeterministicChangeShadowCompiler.compilePreview(
            goal = goal,
            operationId = "change-1",
            sourceAssetId = "asset-1",
            certificate = certificate,
            change = LocalDeterministicChange.ColorTint("#22aa66", 0.65)
        )

        assertTrue(compilation.executable)
        assertTrue(certificate.readyForPreview(goal.stateFingerprint))
        assertFalse(certificate.readyForMutation(goal.stateFingerprint))
        val node = compilation.graph.nodes.values.single()
        assertEquals(LocalDeterministicChangePreviewAction.ACTION_ID, node.actionId)
        assertEquals(AgentActionPhase.TRANSFORM, node.phase)
        assertEquals(setOf(AgentStateEffect.ADD_ARTIFACT), node.effects)
        assertFalse(node.destructive)
        val parameters = JSONObject(node.parametersJson)
        assertEquals("color_tint", parameters.getString("effect_kind"))
        assertEquals("#22AA66", parameters.getString("color_hex"))
        assertTrue(parameters.getBoolean("preview_only"))
        assertFalse(parameters.getBoolean("writeback"))
        assertEquals(
            setOf(AgentDataKind.ASSET_SET, AgentDataKind.MASK_TRACK, AgentDataKind.SCOPE_CERTIFICATE),
            compilation.graph.externalBindings.map { it.value.kind }.toSet()
        )
    }

    @Test
    fun fakeBackendCanMaterializeTemporaryPreviewWithoutCommitEffect() {
        val compilation = LocalDeterministicChangeShadowCompiler.compilePreview(
            changeGoal(),
            "change-1",
            "asset-1",
            certificate(),
            LocalDeterministicChange.Blur(12.0)
        )

        val result = AgentDagExecutionEngine.runToTerminal(compilation, AgentDagNodeExecutor { node, inputs ->
            assertEquals(LocalDeterministicChangePreviewAction.ACTION_ID, node.actionId)
            assertTrue(inputs.single("scope_certificate") is AgentDagRuntimeValue.ScopeCertificate)
            AgentDagNodeExecutionResult(mapOf(
                "preview" to listOf(
                    AgentDagRuntimeValue.Artifact("preview-artifact", AgentDataKind.MEDIA_ARTIFACT)
                )
            ))
        })

        assertEquals(AgentDagRunStatus.FINISHED, result.runState.status)
        assertEquals(listOf("preview-change-1"), result.executedNodeIds)
        assertEquals(
            AgentDataKind.MEDIA_ARTIFACT,
            result.values.getValue(AgentDagValueKey("preview-change-1", "preview")).single().kind
        )
    }

    @Test
    fun staleCertificateIsRejectedByCompilerAndDagValidator() {
        val goal = changeGoal()
        val valid = LocalDeterministicChangeShadowCompiler.compilePreview(
            goal,
            "change-1",
            "asset-1",
            certificate(),
            LocalDeterministicChange.Blur(8.0)
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalDeterministicChangeShadowCompiler.compilePreview(
                goal.copy(stateFingerprint = "state-new"),
                "change-1",
                "asset-1",
                certificate(),
                LocalDeterministicChange.Blur(8.0)
            )
        }

        val staleBindings = valid.graph.externalBindings.map { binding ->
            if (binding.value is AgentDagExternalValue.ScopeCertificate) {
                binding.copy(value = AgentDagExternalValue.ScopeCertificate(
                    "scope-1", "state-old", "certificate-old"
                ))
            } else {
                binding
            }
        }
        val validation = AgentDagValidator.validate(valid.graph.copy(externalBindings = staleBindings))
        assertTrue(validation.diagnostics.any { it.code == "stale_scope_certificate" })
    }

    @Test
    fun localEffectParametersAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalDeterministicChange.ColorTint("blue", 0.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalDeterministicChange.Blur(100.0)
        }
    }

    private fun changeGoal() = AgentGoalIr(
        goalId = "goal-change",
        userGoal = "把猫调成绿色",
        stateFingerprint = "state-current",
        operations = listOf(AgentGoalOperation(
            operationId = "change-1",
            kind = AgentGoalOperationKind.CHANGE_CONTENT,
            scope = AgentGoalScope.CurrentAssets,
            target = AgentGoalTarget.GroundedContentNode("content-1", 3, "object-1")
        ))
    )

    private fun certificate() = AgentScopeCertificate(
        certificateId = "scope-1",
        contentGraphId = "content-1",
        contentGraphVersion = 3,
        objectTrackNodeId = "object-1",
        maskTrackNodeId = "mask-1",
        stateFingerprint = "state-current",
        expectedFrameCount = 2,
        observations = listOf(
            ValidatedTrackObservation(0, 0, TrackObservationStatus.VISIBLE_VALIDATED, 40_000, null),
            ValidatedTrackObservation(1, 1_000, TrackObservationStatus.ABSENT_MODEL, 0, 0.0)
        ),
        uniqueTarget = true,
        protectionChecksPassed = true,
        validationPolicy = TrackValidationPolicy("candidate-v1", 0.10)
    )
}
