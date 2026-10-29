package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContentGraphTest {
    @Test
    fun patchAddsVersionedNodesAndEdgesAtomically() {
        val graph = AgentContentGraph("content-1")
        val patch = ContentGraphPatch(
            patchId = "patch-1",
            baseVersion = 0,
            author = "test",
            mutations = listOf(
                ContentGraphMutation.AddNode(ContentNode("asset", ContentNodeKind.LIVE_ASSET)),
                ContentGraphMutation.AddNode(ContentNode("video", ContentNodeKind.VIDEO_TRACK)),
                ContentGraphMutation.AddEdge(ContentEdge(
                    "contains-video", "asset", "video", ContentEdgeKind.CONTAINS
                ))
            )
        )

        val updated = AgentContentGraphReducer.apply(graph, patch)

        assertEquals(1, updated.version)
        assertEquals(setOf("asset", "video"), updated.nodes.keys)
        assertEquals(setOf("contains-video"), updated.edges.keys)
        assertTrue(AgentContentGraphValidator.validate(updated).valid)
        assertTrue(graph.nodes.isEmpty())
    }

    @Test
    fun stalePatchAndNodeRevisionAreRejected() {
        val graph = AgentContentGraph(
            "content-1",
            version = 2,
            nodes = mapOf("object" to ContentNode("object", ContentNodeKind.OBJECT_TRACK, revision = 3))
        )

        assertThrows(IllegalArgumentException::class.java) {
            AgentContentGraphReducer.apply(graph, ContentGraphPatch(
                "stale-graph", 1, listOf(ContentGraphMutation.RemoveNode("object")), "test"
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentContentGraphReducer.apply(graph, ContentGraphPatch(
                "stale-node",
                2,
                listOf(ContentGraphMutation.UpdateNode("object", 2, mapOf("label" to "cat"))),
                "test"
            ))
        }
    }

    @Test
    fun removingNodeAlsoRemovesIncidentEdges() {
        val graph = AgentContentGraph(
            "content-1",
            version = 1,
            nodes = mapOf(
                "asset" to ContentNode("asset", ContentNodeKind.LIVE_ASSET),
                "object" to ContentNode("object", ContentNodeKind.OBJECT_TRACK)
            ),
            edges = mapOf(
                "contains" to ContentEdge("contains", "asset", "object", ContentEdgeKind.CONTAINS)
            )
        )

        val updated = AgentContentGraphReducer.apply(graph, ContentGraphPatch(
            "remove-object", 1, listOf(ContentGraphMutation.RemoveNode("object", 1)), "test"
        ))

        assertFalse("object" in updated.nodes)
        assertTrue(updated.edges.isEmpty())
    }
}
