package com.vivo.lpa

import org.json.JSONObject

/** First local-change MVP: bounded, deterministic effects rendered to a temporary preview. */
sealed interface LocalDeterministicChange {
    val effectKind: String
    fun parameters(): JSONObject

    companion object {
        /** Qwen chooses the canonical effect; deterministic code only validates typed parameters. */
        fun fromOperation(operation: AgentGoalOperation): LocalDeterministicChange? = runCatching {
            fun text(name: String): String? =
                (operation.parameters[name]?.value as? AgentGoalValue.Text)?.value
            fun number(name: String): Double? =
                (operation.parameters[name]?.value as? AgentGoalValue.Number)?.value

            when (text("effect_kind")) {
                "color_tint" -> ColorTint(
                    requireNotNull(text("color_hex")),
                    requireNotNull(number("intensity"))
                )
                "blur" -> Blur(requireNotNull(number("blur_radius_px")))
                else -> null
            }
        }.getOrNull()
    }

    data class ColorTint(val colorHex: String, val intensity: Double) : LocalDeterministicChange {
        init {
            require(Regex("#[0-9a-fA-F]{6}").matches(colorHex)) { "colorHex must be #RRGGBB" }
            require(intensity in 0.0..1.0)
        }

        override val effectKind = "color_tint"
        override fun parameters(): JSONObject = JSONObject()
            .put("effect_kind", effectKind)
            .put("color_hex", colorHex.uppercase())
            .put("intensity", intensity)
    }

    data class Blur(val radiusPx: Double) : LocalDeterministicChange {
        init {
            require(radiusPx in 0.5..64.0)
        }

        override val effectKind = "blur"
        override fun parameters(): JSONObject = JSONObject()
            .put("effect_kind", effectKind)
            .put("blur_radius_px", radiusPx)
    }

}

/** Shadow-only action contract. It creates a temporary artifact and cannot replace or publish media. */
object LocalDeterministicChangePreviewAction {
    const val ACTION_ID = "local_mask_change_preview_shadow"

    val spec = MobileToolSpec(
        id = ACTION_ID,
        family = ToolFamily.STYLE,
        description = "Render a mask-scoped deterministic change into a temporary preview",
        contract = AgentActionContract(
            phase = AgentActionPhase.TRANSFORM,
            inputPorts = listOf(
                AgentPortSpec(
                    "media",
                    setOf(
                        AgentDataKind.ASSET_SET,
                        AgentDataKind.IMAGE,
                        AgentDataKind.VIDEO,
                        AgentDataKind.LIVE_PHOTO
                    ),
                    minItems = 1,
                    maxItems = 1
                ),
                AgentPortSpec("mask_track", setOf(AgentDataKind.MASK_TRACK)),
                AgentPortSpec("scope_certificate", setOf(AgentDataKind.SCOPE_CERTIFICATE))
            ),
            outputPorts = listOf(
                AgentPortSpec("preview", setOf(AgentDataKind.MEDIA_ARTIFACT))
            ),
            parameters = listOf(
                AgentParameterSpec(
                    "effect_kind",
                    AgentParameterType.STRING,
                    required = true,
                    enumValues = setOf("color_tint", "blur")
                ),
                AgentParameterSpec("color_hex", AgentParameterType.STRING),
                AgentParameterSpec(
                    "intensity", AgentParameterType.DECIMAL, minimum = 0.0, maximum = 1.0
                ),
                AgentParameterSpec(
                    "blur_radius_px", AgentParameterType.DECIMAL, unit = "px",
                    minimum = 0.5, maximum = 64.0
                ),
                AgentParameterSpec("preview_only", AgentParameterType.BOOLEAN, required = true),
                AgentParameterSpec("writeback", AgentParameterType.BOOLEAN, required = true)
            ).associateBy { it.name },
            allowUnknownParameters = false,
            effects = setOf(AgentStateEffect.ADD_ARTIFACT),
            resources = setOf(
                AgentResourceClaim(
                    AgentResourceKind.GPU,
                    exclusive = true,
                    estimatedPeakMemoryMb = 384,
                    thermalWeight = 2
                ),
                AgentResourceClaim(AgentResourceKind.VIDEO_CODEC, exclusive = true),
                AgentResourceClaim(AgentResourceKind.STORAGE_IO)
            ),
            postconditionIds = setOf(
                "preview_artifact_exists",
                "source_media_unchanged",
                "scope_certificate_fresh"
            )
        ),
        destructive = false,
        idempotent = true,
        observationBarrier = true,
        loopOnly = true,
        loopVisible = false
    )
}

/** Deterministic lowering used by tests and future shadow bridge; no executor is registered. */
object LocalDeterministicChangeShadowCompiler {
    fun compilePreview(
        goal: AgentGoalIr,
        operationId: String,
        sourceAssetId: String,
        certificate: AgentScopeCertificate,
        change: LocalDeterministicChange,
        graphVersion: Int = 1
    ): AgentDagCompilation {
        val operation = requireNotNull(goal.operations.firstOrNull { it.operationId == operationId }) {
            "missing operation $operationId"
        }
        require(operation.kind == AgentGoalOperationKind.CHANGE_CONTENT)
        val target = operation.target as? AgentGoalTarget.GroundedContentNode
            ?: throw IllegalArgumentException("local change requires a grounded content target")
        require(sourceAssetId.isNotBlank())
        require(certificate.readyForPreview(goal.stateFingerprint)) {
            "scope certificate is not ready for preview"
        }
        require(certificate.contentGraphId == target.contentGraphId)
        require(certificate.contentGraphVersion == target.contentGraphVersion)
        require(certificate.objectTrackNodeId == target.nodeId)

        val nodeId = "preview-$operationId"
        val parameters = change.parameters()
            .put("preview_only", true)
            .put("writeback", false)
        val builder = AgentDagBuilder(
            graphId = "dag-${goal.goalId}-local-preview",
            graphVersion = graphVersion,
            goalId = goal.goalId,
            stateFingerprint = goal.stateFingerprint,
            revisionReason = "local deterministic change preview"
        ).addAction(
            nodeId = nodeId,
            operationId = operationId,
            spec = LocalDeterministicChangePreviewAction.spec,
            parametersJson = parameters.toString()
        ).bindExternal(
            nodeId,
            "media",
            AgentDagExternalValue.AssetSet(listOf(sourceAssetId), goal.stateFingerprint)
        ).bindExternal(
            nodeId,
            "mask_track",
            AgentDagExternalValue.ContentNode(
                certificate.contentGraphId,
                certificate.contentGraphVersion,
                certificate.maskTrackNodeId,
                AgentDataKind.MASK_TRACK
            )
        ).bindExternal(
            nodeId,
            "scope_certificate",
            AgentDagExternalValue.ScopeCertificate(
                certificate.certificateId,
                certificate.stateFingerprint,
                certificate.evidenceFingerprint
            )
        )
        return builder.compile(goal)
    }
}
