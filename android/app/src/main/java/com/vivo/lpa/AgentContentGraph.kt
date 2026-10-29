package com.vivo.lpa

import org.json.JSONArray
import org.json.JSONObject

/** Domain objects that can be targeted by future add/change/remove operations. */
enum class ContentNodeKind {
    MEDIA_ASSET,
    LIVE_ASSET,
    MEDIA_ARTIFACT,
    IMAGE_TRACK,
    VIDEO_TRACK,
    AUDIO_TRACK,
    OBJECT_TRACK,
    MASK_TRACK,
    REGION,
    LAYER,
    LAYOUT,
    METADATA
}

data class ContentNode(
    val nodeId: String,
    val kind: ContentNodeKind,
    val revision: Int = 1,
    val attributes: Map<String, String> = emptyMap(),
    val provenance: String = "runtime"
) {
    init {
        require(nodeId.isNotBlank()) { "content node id cannot be blank" }
        require(revision > 0) { "content node revision must be positive" }
    }
}

enum class ContentEdgeKind {
    CONTAINS,
    DERIVED_FROM,
    SYNCHRONIZED_WITH,
    TARGETS,
    MASKS,
    OVERLAYS,
    ANCHORED_TO
}

data class ContentEdge(
    val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val kind: ContentEdgeKind,
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(edgeId.isNotBlank()) { "content edge id cannot be blank" }
        require(fromNodeId.isNotBlank() && toNodeId.isNotBlank()) {
            "content edge endpoints cannot be blank"
        }
    }
}

data class AgentContentGraph(
    val graphId: String,
    val schemaVersion: Int = 1,
    val version: Int = 0,
    val sourceAssetIds: List<String> = emptyList(),
    val nodes: Map<String, ContentNode> = emptyMap(),
    val edges: Map<String, ContentEdge> = emptyMap()
) {
    init {
        require(graphId.isNotBlank()) { "content graph id cannot be blank" }
        require(schemaVersion > 0) { "content graph schema version must be positive" }
        require(version >= 0) { "content graph version cannot be negative" }
    }
}

sealed interface ContentGraphMutation {
    data class AddNode(val node: ContentNode) : ContentGraphMutation
    data class UpdateNode(
        val nodeId: String,
        val expectedRevision: Int,
        val attributes: Map<String, String>,
        val replaceAttributes: Boolean = false
    ) : ContentGraphMutation
    data class RemoveNode(val nodeId: String, val expectedRevision: Int? = null) : ContentGraphMutation
    data class AddEdge(val edge: ContentEdge) : ContentGraphMutation
    data class RemoveEdge(val edgeId: String) : ContentGraphMutation
}

data class ContentGraphPatch(
    val patchId: String,
    val baseVersion: Int,
    val mutations: List<ContentGraphMutation>,
    val author: String,
    val reason: String = ""
) {
    init {
        require(patchId.isNotBlank()) { "content patch id cannot be blank" }
        require(baseVersion >= 0) { "content patch base version cannot be negative" }
        require(author.isNotBlank()) { "content patch author cannot be blank" }
        require(mutations.isNotEmpty()) { "content patch cannot be empty" }
    }
}

data class ContentGraphDiagnostic(
    val code: String,
    val message: String,
    val nodeId: String? = null,
    val edgeId: String? = null
)

data class ContentGraphValidation(val errors: List<ContentGraphDiagnostic>) {
    val valid: Boolean get() = errors.isEmpty()
}

data class AgentContentGraphSnapshot(
    val graph: AgentContentGraph,
    val validation: ContentGraphValidation,
    val stateFingerprint: String
)

data class ContentAssetDescriptor(
    val assetId: String,
    val mediaFormat: String,
    val hasImageTrack: Boolean,
    val hasVideoTrack: Boolean,
    val attributes: Map<String, String> = emptyMap()
)

object AgentContentGraphValidator {
    fun validate(graph: AgentContentGraph): ContentGraphValidation {
        val errors = mutableListOf<ContentGraphDiagnostic>()
        graph.nodes.forEach { (key, node) ->
            if (key != node.nodeId) {
                errors += ContentGraphDiagnostic(
                    "node_key_mismatch", "node map key $key does not match ${node.nodeId}", node.nodeId
                )
            }
        }
        graph.edges.forEach { (key, edge) ->
            if (key != edge.edgeId) {
                errors += ContentGraphDiagnostic(
                    "edge_key_mismatch", "edge map key $key does not match ${edge.edgeId}", edgeId = edge.edgeId
                )
            }
            if (edge.fromNodeId !in graph.nodes) {
                errors += ContentGraphDiagnostic(
                    "missing_edge_source", "edge ${edge.edgeId} source ${edge.fromNodeId} is missing",
                    edgeId = edge.edgeId
                )
            }
            if (edge.toNodeId !in graph.nodes) {
                errors += ContentGraphDiagnostic(
                    "missing_edge_target", "edge ${edge.edgeId} target ${edge.toNodeId} is missing",
                    edgeId = edge.edgeId
                )
            }
        }
        return ContentGraphValidation(errors)
    }
}

/** Applies one content edit atomically; failed validation leaves the previous graph untouched. */
object AgentContentGraphReducer {
    fun apply(graph: AgentContentGraph, patch: ContentGraphPatch): AgentContentGraph {
        require(patch.baseVersion == graph.version) {
            "stale content patch ${patch.patchId}: base=${patch.baseVersion}, current=${graph.version}"
        }
        val nodes = graph.nodes.toMutableMap()
        val edges = graph.edges.toMutableMap()

        patch.mutations.forEach { mutation ->
            when (mutation) {
                is ContentGraphMutation.AddNode -> {
                    require(mutation.node.nodeId !in nodes) {
                        "content node ${mutation.node.nodeId} already exists"
                    }
                    nodes[mutation.node.nodeId] = mutation.node
                }
                is ContentGraphMutation.UpdateNode -> {
                    val current = nodes[mutation.nodeId]
                        ?: throw IllegalArgumentException("content node ${mutation.nodeId} is missing")
                    require(current.revision == mutation.expectedRevision) {
                        "stale content node ${mutation.nodeId}: expected=${mutation.expectedRevision}, current=${current.revision}"
                    }
                    val attributes = if (mutation.replaceAttributes) {
                        mutation.attributes
                    } else {
                        current.attributes + mutation.attributes
                    }
                    nodes[mutation.nodeId] = current.copy(
                        revision = current.revision + 1,
                        attributes = attributes
                    )
                }
                is ContentGraphMutation.RemoveNode -> {
                    val current = nodes[mutation.nodeId]
                        ?: throw IllegalArgumentException("content node ${mutation.nodeId} is missing")
                    mutation.expectedRevision?.let { expected ->
                        require(current.revision == expected) {
                            "stale content node ${mutation.nodeId}: expected=$expected, current=${current.revision}"
                        }
                    }
                    nodes.remove(mutation.nodeId)
                    edges.entries.removeAll {
                        it.value.fromNodeId == mutation.nodeId || it.value.toNodeId == mutation.nodeId
                    }
                }
                is ContentGraphMutation.AddEdge -> {
                    require(mutation.edge.edgeId !in edges) {
                        "content edge ${mutation.edge.edgeId} already exists"
                    }
                    require(mutation.edge.fromNodeId in nodes && mutation.edge.toNodeId in nodes) {
                        "content edge ${mutation.edge.edgeId} has a missing endpoint"
                    }
                    edges[mutation.edge.edgeId] = mutation.edge
                }
                is ContentGraphMutation.RemoveEdge -> {
                    require(edges.remove(mutation.edgeId) != null) {
                        "content edge ${mutation.edgeId} is missing"
                    }
                }
            }
        }

        val updated = graph.copy(version = graph.version + 1, nodes = nodes, edges = edges)
        val validation = AgentContentGraphValidator.validate(updated)
        require(validation.valid) {
            validation.errors.joinToString("; ") { "${it.code}: ${it.message}" }
        }
        return updated
    }
}

/** Projects current runtime facts into a versioned content graph without exposing file paths. */
object AgentContentGraphFactory {
    fun fromState(state: AgentRuntimeState): AgentContentGraphSnapshot {
        val fingerprint = AgentStateFingerprint.of(state)
        val descriptors = state.sourceAssets.map { asset ->
            ContentAssetDescriptor(
                assetId = asset.assetId,
                mediaFormat = asset.metadata.optString("media_format", "unknown"),
                hasImageTrack = asset.imageFile != null,
                hasVideoTrack = asset.videoFile != null,
                attributes = mapOf(
                    "display_name" to asset.sourceFile.name,
                    "selected" to (asset.assetId in state.selectedAssetIds).toString()
                )
            )
        }
        var graph = fromDescriptors(
            graphId = "content-${state.turnId}",
            version = state.executedCount,
            descriptors = descriptors
        )
        val nodes = graph.nodes.toMutableMap()
        val edges = graph.edges.toMutableMap()

        val artifacts = state.artifacts.values.sortedBy { it.artifactId }
        artifacts.forEach { artifact ->
            val artifactNodeId = artifactNodeId(artifact.artifactId)
            nodes[artifactNodeId] = ContentNode(
                artifactNodeId,
                ContentNodeKind.MEDIA_ARTIFACT,
                attributes = mapOf(
                    "artifact_id" to artifact.artifactId,
                    "artifact_kind" to artifact.kind.name,
                    "producer_step_id" to artifact.producerStepId,
                    "validated" to artifact.validated.toString(),
                    "published" to artifact.published.toString()
                ),
                provenance = artifact.producerStepId
            )
        }
        artifacts.forEach { artifact ->
            val artifactNodeId = artifactNodeId(artifact.artifactId)
            val producer = state.plan?.steps?.firstOrNull { it.stepId == artifact.producerStepId }
            producer?.inputs.orEmpty().forEachIndexed { index, input ->
                when (input) {
                    is AgentInputRef.AssetSet -> input.assetIds.forEachIndexed { assetIndex, assetId ->
                        val source = assetNodeId(assetId)
                        if (source in nodes) {
                            val edge = ContentEdge(
                                "derived-$artifactNodeId-$index-$assetIndex",
                                artifactNodeId,
                                source,
                                ContentEdgeKind.DERIVED_FROM
                            )
                            edges[edge.edgeId] = edge
                        }
                    }
                    is AgentInputRef.Artifact -> {
                        val source = artifactNodeId(input.artifactId)
                        if (source in nodes) {
                            val edge = ContentEdge(
                                "derived-$artifactNodeId-$index",
                                artifactNodeId,
                                source,
                                ContentEdgeKind.DERIVED_FROM
                            )
                            edges[edge.edgeId] = edge
                        }
                    }
                    is AgentInputRef.Layout -> Unit
                }
            }
        }

        state.layout?.let { layout ->
            val layoutNodeId = "layout:${state.layoutVersion}"
            nodes[layoutNodeId] = ContentNode(
                layoutNodeId,
                ContentNodeKind.LAYOUT,
                revision = state.layoutVersion.coerceAtLeast(1),
                attributes = mapOf(
                    "template_id" to layout.optString("template_id"),
                    "slot_count" to (layout.optJSONArray("items")?.length() ?: 0).toString()
                ),
                provenance = "runtime-layout"
            )
            val items = layout.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val assetId = items.optJSONObject(index)?.optString("asset_id").orEmpty()
                    val target = assetNodeId(assetId)
                    if (target in nodes) {
                        val edge = ContentEdge(
                            "layout-target-$index",
                            layoutNodeId,
                            target,
                            ContentEdgeKind.TARGETS,
                            mapOf("slot" to index.toString())
                        )
                        edges[edge.edgeId] = edge
                    }
                }
            }
        }

        graph = graph.copy(nodes = nodes, edges = edges)
        return AgentContentGraphSnapshot(
            graph,
            AgentContentGraphValidator.validate(graph),
            fingerprint
        )
    }

    fun fromDescriptors(
        graphId: String,
        version: Int,
        descriptors: List<ContentAssetDescriptor>
    ): AgentContentGraph {
        val nodes = linkedMapOf<String, ContentNode>()
        val edges = linkedMapOf<String, ContentEdge>()
        descriptors.forEach { descriptor ->
            val rootId = assetNodeId(descriptor.assetId)
            nodes[rootId] = ContentNode(
                rootId,
                if (descriptor.mediaFormat.equals("livephoto", true)) {
                    ContentNodeKind.LIVE_ASSET
                } else {
                    ContentNodeKind.MEDIA_ASSET
                },
                attributes = descriptor.attributes + mapOf(
                    "asset_id" to descriptor.assetId,
                    "media_format" to descriptor.mediaFormat
                ),
                provenance = "runtime-asset"
            )
            if (descriptor.hasImageTrack) {
                addTrack(nodes, edges, rootId, "image", ContentNodeKind.IMAGE_TRACK)
            }
            if (descriptor.hasVideoTrack) {
                addTrack(nodes, edges, rootId, "video", ContentNodeKind.VIDEO_TRACK)
            }
            val metadataId = "$rootId:metadata"
            nodes[metadataId] = ContentNode(
                metadataId,
                ContentNodeKind.METADATA,
                attributes = mapOf("media_format" to descriptor.mediaFormat),
                provenance = "runtime-metadata"
            )
            val edge = ContentEdge(
                "contains-$rootId-metadata",
                rootId,
                metadataId,
                ContentEdgeKind.CONTAINS
            )
            edges[edge.edgeId] = edge
        }
        return AgentContentGraph(
            graphId = graphId,
            version = version,
            sourceAssetIds = descriptors.map { it.assetId },
            nodes = nodes,
            edges = edges
        )
    }

    private fun addTrack(
        nodes: MutableMap<String, ContentNode>,
        edges: MutableMap<String, ContentEdge>,
        rootId: String,
        suffix: String,
        kind: ContentNodeKind
    ) {
        val trackId = "$rootId:$suffix"
        nodes[trackId] = ContentNode(trackId, kind, provenance = "runtime-track")
        val edge = ContentEdge(
            "contains-$rootId-$suffix",
            rootId,
            trackId,
            ContentEdgeKind.CONTAINS
        )
        edges[edge.edgeId] = edge
    }

    private fun assetNodeId(assetId: String): String = "asset:$assetId"
    private fun artifactNodeId(artifactId: String): String = "artifact:$artifactId"
}

object AgentContentGraphJson {
    fun toJson(snapshot: AgentContentGraphSnapshot): JSONObject = JSONObject()
        .put("graph_id", snapshot.graph.graphId)
        .put("schema_version", snapshot.graph.schemaVersion)
        .put("graph_version", snapshot.graph.version)
        .put("state_fingerprint", snapshot.stateFingerprint)
        .put("valid", snapshot.validation.valid)
        .put("source_asset_ids", JSONArray(snapshot.graph.sourceAssetIds))
        .put("nodes", JSONArray(snapshot.graph.nodes.values.map { node ->
            JSONObject()
                .put("node_id", node.nodeId)
                .put("kind", node.kind.name)
                .put("revision", node.revision)
                .put("attributes", JSONObject(node.attributes))
                .put("provenance", node.provenance)
        }))
        .put("edges", JSONArray(snapshot.graph.edges.values.map { edge ->
            JSONObject()
                .put("edge_id", edge.edgeId)
                .put("from_node_id", edge.fromNodeId)
                .put("to_node_id", edge.toNodeId)
                .put("kind", edge.kind.name)
                .put("attributes", JSONObject(edge.attributes))
        }))
        .put("errors", JSONArray(snapshot.validation.errors.map { error ->
            JSONObject()
                .put("code", error.code)
                .put("message", error.message)
                .put("node_id", error.nodeId ?: JSONObject.NULL)
                .put("edge_id", error.edgeId ?: JSONObject.NULL)
        }))
}
