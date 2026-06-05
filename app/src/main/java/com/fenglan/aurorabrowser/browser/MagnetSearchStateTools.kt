package com.fenglan.aurorabrowser.browser

data class MagnetSourceHealth(
    val sourceId: String,
    val lastStatus: MagnetSourceStatus,
    val lastDurationMs: Long,
    val lastResultCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val verificationCount: Int,
    val updatedAt: Long,
)

enum class MagnetSourceStatus(val key: String) {
    SUCCESS("success"),
    EMPTY("empty"),
    FAILED("failed"),
    VERIFICATION("verification"),
    ;

    companion object {
        fun fromKey(value: String?): MagnetSourceStatus {
            return entries.firstOrNull { it.key == value } ?: FAILED
        }
    }
}

object MagnetSearchStateTools {
    const val DEFAULT_SOURCE_CONCURRENCY = 3
    const val MAX_CACHED_RESULTS_PER_SOURCE = 30

    fun statusFor(outcome: MagnetSearchOutcome): MagnetSourceStatus {
        return when {
            outcome.requiresVerification -> MagnetSourceStatus.VERIFICATION
            outcome.errorMessage != null -> MagnetSourceStatus.FAILED
            outcome.results.isNotEmpty() -> MagnetSourceStatus.SUCCESS
            else -> MagnetSourceStatus.EMPTY
        }
    }

    fun updatedHealth(
        previous: MagnetSourceHealth?,
        sourceId: String,
        outcome: MagnetSearchOutcome,
        durationMs: Long,
        now: Long,
    ): MagnetSourceHealth {
        val status = statusFor(outcome)
        return MagnetSourceHealth(
            sourceId = sourceId,
            lastStatus = status,
            lastDurationMs = durationMs.coerceAtLeast(0L),
            lastResultCount = outcome.results.size,
            successCount = (previous?.successCount ?: 0) + if (status == MagnetSourceStatus.SUCCESS) 1 else 0,
            failureCount = (previous?.failureCount ?: 0) + if (status == MagnetSourceStatus.FAILED) 1 else 0,
            verificationCount = (previous?.verificationCount ?: 0) + if (status == MagnetSourceStatus.VERIFICATION) 1 else 0,
            updatedAt = now,
        )
    }

    fun rankSourcesForSearch(
        sources: List<MagnetSearchSource>,
        health: Map<String, MagnetSourceHealth>,
    ): List<MagnetSearchSource> {
        return sources.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<MagnetSearchSource>> {
                    health[it.value.id]?.let(::rankScore) ?: 0.0
                }.thenBy { it.index },
            )
            .map { it.value }
    }

    fun trimOutcomesForCache(outcomes: List<MagnetSearchOutcome>): List<MagnetSearchOutcome> {
        return outcomes.map { outcome ->
            outcome.copy(results = outcome.results.take(MAX_CACHED_RESULTS_PER_SOURCE))
        }
    }

    private fun rankScore(health: MagnetSourceHealth): Double {
        val attempts = (health.successCount + health.failureCount + health.verificationCount)
            .coerceAtLeast(1)
        val successRate = health.successCount.toDouble() / attempts
        val resultBonus = health.lastResultCount.coerceAtMost(20) / 20.0
        val durationPenalty = health.lastDurationMs.coerceIn(0L, 60_000L) / 60_000.0
        val statusScore = when (health.lastStatus) {
            MagnetSourceStatus.SUCCESS -> 1.5
            MagnetSourceStatus.EMPTY -> -0.25
            MagnetSourceStatus.FAILED -> -1.25
            MagnetSourceStatus.VERIFICATION -> -2.0
        }
        return statusScore + successRate * 2.0 + resultBonus - durationPenalty
    }
}
