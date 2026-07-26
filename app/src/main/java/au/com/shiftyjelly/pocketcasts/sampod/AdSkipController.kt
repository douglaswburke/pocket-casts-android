package au.com.shiftyjelly.pocketcasts.sampod

/**
 * Pure ad-skip decision logic — NO Android / player dependencies, so it's unit-testable and
 * safe to wire into PlaybackManager. Mirrors the validated SamPod MVP behaviour:
 *  - a position poll (driven by PlaybackManager.playbackStateFlow) asks "am I inside an ad?"
 *  - if yes, return the ms to seek to (ad end); the caller does the seek + emits a notice.
 *  - a per-ad re-fire guard prevents a second seek fighting the async seekTo settling.
 *
 * Confidence gate: only auto-skip ads at/above [minConfidence]; lower-confidence windows are
 * still surfaced as timeline bars (manual skip) but not auto-skipped. (design doc §Playback)
 */
class AdSkipController(
    private val minConfidence: Double = 0.7,
    private val refireGuardMs: Int = 1500,
) {
    private var lastSkippedEndMs: Int = -1
    private var lastSkipAtMs: Int = -1

    data class SkipDecision(val seekToMs: Int, val ad: AdSkip)

    /**
     * @param positionMs current playback position
     * @param skips the episode's ad windows (from the Sidecar)
     * @return a SkipDecision if the playhead is inside an auto-skippable ad, else null.
     */
    fun check(positionMs: Int, skips: List<AdSkip>): SkipDecision? {
        for (ad in skips) {
            if (ad.confidence < minConfidence) continue
            if (positionMs in ad.startMs until ad.endMs) {
                // debounce: don't re-fire for the same ad-end within the guard window
                if (ad.endMs == lastSkippedEndMs && (positionMs - lastSkipAtMs) < refireGuardMs) {
                    return null
                }
                lastSkippedEndMs = ad.endMs
                lastSkipAtMs = positionMs
                return SkipDecision(seekToMs = ad.endMs, ad = ad)
            }
        }
        return null
    }

    /** Ad windows for the timeline UI (all of them, including sub-threshold for manual skip). */
    fun timelineWindows(skips: List<AdSkip>): List<AdSkip> = skips.sortedBy { it.startMs }

    fun reset() {
        lastSkippedEndMs = -1
        lastSkipAtMs = -1
    }

    companion object {
        /** Standard jump offsets for the ±10s / ±30s buttons (design doc §Playback). */
        const val SKIP_BACK_10_MS = -10_000
        const val SKIP_FWD_30_MS = 30_000
        const val SKIP_BACK_30_MS = -30_000
        const val SKIP_FWD_10_MS = 10_000
    }
}
