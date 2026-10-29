package com.vivo.lpa

/** Domain objects that can be targeted by future add/change/remove operations. */
enum class ContentNodeKind {
    LIVE_ASSET,
    IMAGE_TRACK,
    VIDEO_TRACK,
    AUDIO_TRACK,
    OBJECT_TRACK,
    MASK_TRACK,
    REGION,
    LAYER,
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
