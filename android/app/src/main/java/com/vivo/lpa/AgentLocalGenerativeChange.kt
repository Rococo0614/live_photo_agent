package com.vivo.lpa

import org.json.JSONObject

/** Typed model-authored request for a separately gated masked-generation preview. */
data class LocalGenerativeChange(
    val generationPrompt: String,
    val promptSourceText: String
) {
    init {
        require(generationPrompt.isNotBlank() && generationPrompt.length <= 160)
        require(generationPrompt.all { it.isWhitespace() || it.code in 0x20..0x7e }) {
            "generation prompt must be a bounded ASCII visual concept"
        }
        require(promptSourceText.isNotBlank())
    }

    val effectKind = EFFECT_KIND

    fun parameters(): JSONObject = JSONObject()
        .put("effect_kind", effectKind)
        .put("generation_prompt", generationPrompt)
        .put("generation_prompt_source_text", promptSourceText)

    companion object {
        const val EFFECT_KIND = "masked_replace"

        fun fromOperation(operation: AgentGoalOperation): LocalGenerativeChange? = runCatching {
            fun argument(name: String) = operation.parameters[name]
            fun text(name: String) = (argument(name)?.value as? AgentGoalValue.Text)?.value
            if (text("effect_kind") != EFFECT_KIND) return null
            val prompt = requireNotNull(text("generation_prompt"))
            val sourceText = requireNotNull(argument("generation_prompt")?.sourceText)
            require(operation.kind == AgentGoalOperationKind.CHANGE_CONTENT)
            require(operation.target is AgentGoalTarget.Semantic ||
                operation.target is AgentGoalTarget.GroundedContentNode)
            require(operation.parameters.keys == setOf("effect_kind", "generation_prompt"))
            LocalGenerativeChange(prompt, sourceText)
        }.getOrNull()
    }
}

/** Hidden shadow action. It can only create a process-local static preview artifact. */
object LocalMaskedGenerationPreviewAction {
    const val ACTION_ID = "local_mask_generation_preview_shadow"

    val spec = MobileToolSpec(
        id = ACTION_ID,
        family = ToolFamily.REPLACE,
        description = "Generate a fixture-backed replacement inside a certified mask",
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
                    enumValues = setOf(LocalGenerativeChange.EFFECT_KIND)
                ),
                AgentParameterSpec("generation_prompt", AgentParameterType.STRING, required = true),
                AgentParameterSpec(
                    "generation_prompt_source_text",
                    AgentParameterType.STRING,
                    required = true
                ),
                AgentParameterSpec("preview_only", AgentParameterType.BOOLEAN, required = true),
                AgentParameterSpec("writeback", AgentParameterType.BOOLEAN, required = true),
                AgentParameterSpec("single_frame_only", AgentParameterType.BOOLEAN, required = true)
            ).associateBy { it.name },
            allowUnknownParameters = false,
            effects = setOf(AgentStateEffect.ADD_ARTIFACT),
            resources = setOf(
                AgentResourceClaim(AgentResourceKind.CPU, thermalWeight = 1),
                AgentResourceClaim(AgentResourceKind.STORAGE_IO)
            ),
            postconditionIds = setOf(
                "preview_artifact_exists",
                "source_media_unchanged",
                "scope_certificate_fresh",
                "single_frame_generation_disclosed"
            )
        ),
        destructive = false,
        idempotent = true,
        observationBarrier = true,
        loopOnly = true,
        loopVisible = false
    )
}

object LocalMaskedGenerationShadowCompiler {
    fun compilePreview(
        goal: AgentGoalIr,
        operationId: String,
        sourceAssetId: String,
        certificate: AgentScopeCertificate,
        change: LocalGenerativeChange,
        graphVersion: Int = 1
    ): AgentDagCompilation {
        val operation = requireNotNull(goal.operations.firstOrNull { it.operationId == operationId })
        require(operation.kind == AgentGoalOperationKind.CHANGE_CONTENT)
        val target = operation.target as? AgentGoalTarget.GroundedContentNode
            ?: throw IllegalArgumentException("masked generation requires a grounded content target")
        require(certificate.readyForPreview(goal.stateFingerprint))
        require(certificate.contentGraphId == target.contentGraphId)
        require(certificate.contentGraphVersion == target.contentGraphVersion)
        require(certificate.objectTrackNodeId == target.nodeId)

        val nodeId = "generate-preview-$operationId"
        val parameters = change.parameters()
            .put("preview_only", true)
            .put("writeback", false)
            .put("single_frame_only", true)
        return AgentDagBuilder(
            graphId = "dag-${goal.goalId}-local-generation-preview",
            graphVersion = graphVersion,
            goalId = goal.goalId,
            stateFingerprint = goal.stateFingerprint,
            revisionReason = "masked generation fixture preview"
        ).addAction(
            nodeId,
            operationId,
            LocalMaskedGenerationPreviewAction.spec,
            parameters.toString()
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
        ).compile(goal)
    }
}
