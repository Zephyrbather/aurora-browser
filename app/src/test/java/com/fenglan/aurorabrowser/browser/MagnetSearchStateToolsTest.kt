package com.fenglan.aurorabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class MagnetSearchStateToolsTest {
    private val sourceA = MagnetSearchSource("a", "A", "https://a.example/search?q={query}")
    private val sourceB = MagnetSearchSource("b", "B", "https://b.example/search?q={query}")
    private val sourceC = MagnetSearchSource("c", "C", "https://c.example/search?q={query}")

    @Test
    fun `rankSourcesForSearch prefers successful fast sources and keeps unknown order stable`() {
        val health = mapOf(
            "b" to MagnetSourceHealth(
                sourceId = "b",
                lastStatus = MagnetSourceStatus.SUCCESS,
                lastDurationMs = 900,
                lastResultCount = 12,
                successCount = 4,
                failureCount = 0,
                verificationCount = 0,
                updatedAt = 2,
            ),
            "c" to MagnetSourceHealth(
                sourceId = "c",
                lastStatus = MagnetSourceStatus.VERIFICATION,
                lastDurationMs = 500,
                lastResultCount = 0,
                successCount = 0,
                failureCount = 0,
                verificationCount = 3,
                updatedAt = 3,
            ),
        )

        val ranked = MagnetSearchStateTools.rankSourcesForSearch(listOf(sourceA, sourceB, sourceC), health)

        assertEquals(listOf("b", "a", "c"), ranked.map { it.id })
    }

    @Test
    fun `updatedHealth records status counts and clamps negative duration`() {
        val outcome = MagnetSearchOutcome(
            source = sourceA,
            results = listOf(
                MagnetSearchResult(
                    sourceId = "a",
                    sourceLabel = "A",
                    title = "Ubuntu",
                    pageUrl = null,
                    magnetUrl = "magnet:?xt=urn:btih:abcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    torrentUrl = null,
                ),
            ),
        )

        val health = MagnetSearchStateTools.updatedHealth(
            previous = null,
            sourceId = "a",
            outcome = outcome,
            durationMs = -10,
            now = 42,
        )

        assertEquals(MagnetSourceStatus.SUCCESS, health.lastStatus)
        assertEquals(0L, health.lastDurationMs)
        assertEquals(1, health.lastResultCount)
        assertEquals(1, health.successCount)
        assertEquals(0, health.failureCount)
        assertEquals(0, health.verificationCount)
        assertEquals(42L, health.updatedAt)
    }

    @Test
    fun `updatedHealth counts verification separately from generic failures`() {
        val outcome = MagnetSearchOutcome(
            source = sourceC,
            results = emptyList(),
            errorMessage = MagnetSearchOutcome.ERROR_REQUIRES_VERIFICATION,
        )

        val health = MagnetSearchStateTools.updatedHealth(
            previous = null,
            sourceId = "c",
            outcome = outcome,
            durationMs = 1200,
            now = 42,
        )

        assertEquals(MagnetSourceStatus.VERIFICATION, health.lastStatus)
        assertEquals(0, health.successCount)
        assertEquals(0, health.failureCount)
        assertEquals(1, health.verificationCount)
    }

    @Test
    fun `trimOutcomesForCache keeps source state and caps each source results`() {
        val results = (1..35).map { index ->
            MagnetSearchResult(
                sourceId = "a",
                sourceLabel = "A",
                title = "Result $index",
                pageUrl = "https://a.example/$index",
                magnetUrl = null,
                torrentUrl = null,
            )
        }
        val outcomes = listOf(
            MagnetSearchOutcome(sourceA, results),
            MagnetSearchOutcome(sourceC, emptyList(), "站点暂时不可访问"),
        )

        val trimmed = MagnetSearchStateTools.trimOutcomesForCache(outcomes)

        assertEquals(MagnetSearchStateTools.MAX_CACHED_RESULTS_PER_SOURCE, trimmed.first().results.size)
        assertEquals("站点暂时不可访问", trimmed[1].errorMessage)
    }
}
