package com.ai.assistance.operit.data.stats

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ContextDiagnosticsStoreTest {
    @Test fun requestsAndCompressionRemainChatScopedAndReadOnly() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val sections = mutableListOf(ContextSection(ContextSectionKind.USER, ContextTokenCount(10, ContextTokenSource.ESTIMATE)))
        val references = mutableListOf(ContextMessageReference(1, 23, "user"))
        try {
            ContextDiagnosticsStore.recordRequest(a, sections, recordedAt = 1)
            ContextDiagnosticsStore.recordCompression(a, "manual", null, null, references, 2)
            ContextDiagnosticsStore.recordRequest(b, emptyList<ContextSection>(), recordedAt = 3)
            sections.clear()
            references.clear()
            assertEquals(1, ContextDiagnosticsStore.read(a)!!.request!!.sections.size)
            assertEquals(1, ContextDiagnosticsStore.read(a)!!.compression!!.coveredMessages.size)
            assertNull(ContextDiagnosticsStore.read(b)!!.compression)
            ContextDiagnosticsStore.recordRequest(a, emptyList<ContextSection>(), recordedAt = 4)
            assertEquals("manual", ContextDiagnosticsStore.read(a)!!.compression!!.reason)
            ContextDiagnosticsStore.clear(a)
            assertNull(ContextDiagnosticsStore.read(a))
            assertNotNull(ContextDiagnosticsStore.read(b))
        } finally {
            ContextDiagnosticsStore.clear(a)
            ContextDiagnosticsStore.clear(b)
        }
    }

    @Test fun longRequestsAggregateUnknownAndMixedSourcesWithoutLosingMeaning() {
        val sections = (0 until 1000).map {
            ContextSection(ContextSectionKind.USER, ContextTokenCount(1, ContextTokenSource.ESTIMATE))
        } + listOf(
            ContextSection(ContextSectionKind.USER, null),
            ContextSection(ContextSectionKind.USER, ContextTokenCount(20, ContextTokenSource.SERVER)),
            ContextSection(ContextSectionKind.SYSTEM, null)
        )
        val grouped = ContextRequestDiagnostics(sections, null, ContextWindowLimits(null, null), 1).aggregateByKind()
        assertEquals(2, grouped.size)
        val users = grouped.first()
        assertEquals(1002, users.sectionCount)
        assertEquals(1, users.unknownSectionCount)
        assertEquals(1020L, users.recordedTokens)
        assertEquals(setOf(ContextTokenSource.ESTIMATE, ContextTokenSource.SERVER), users.sources)
        assertFalse(users.isFullyRecorded)
        assertNull(users.firstIndex)
        assertEquals(1, grouped.last().unknownSectionCount)
    }

    @Test fun coverageUsesSnapshotIndexesAndAcceptsDuplicateTimestamps() {
        val coverage = ContextCompressionDiagnostics("manual", null, null,
            listOf(ContextMessageReference(7, 10, "ai"), ContextMessageReference(3, 10, "user")), 1).coverageRange()!!
        assertEquals(2, coverage.messageCount)
        assertEquals(3, coverage.firstIndex)
        assertEquals(7, coverage.lastIndex)
        assertEquals(10L, coverage.firstTimestamp)
        assertEquals(mapOf("user" to 1, "ai" to 1), coverage.roleCounts)
        assertNull(ContextCompressionDiagnostics("manual", null, null, emptyList(), 1).coverageRange())
    }
}