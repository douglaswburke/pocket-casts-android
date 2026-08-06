package au.com.shiftyjelly.pocketcasts.repositories.sampod

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-module hand-off for a fresh SamPod sidecar produced by a mark-a-miss relearn.
 *
 * The mark button lives in the player FEATURE module (PlayerViewModel); the skip engine lives
 * in the APP module (SamPodSkipCoordinator). Neither can reference the other, but both depend
 * on `repositories` — so this object is the seam between them.
 *
 * When a mark POSTs to `/sampod/relearn`, the server returns the UPDATED sidecar in its
 * response. The mark handler publishes it here (episode sampod-id + the sidecar JSON); the
 * coordinator collects it and, if it is for the CURRENTLY PLAYING episode, adopts it live — so
 * the corrected skips take effect immediately instead of only on the next episode-open
 * re-fetch (the read-once bug, SamPod increment 6b, 2026-08-06).
 */
object SamPodRelearnBus {
    data class Relearn(val sampodId: String, val sidecarJson: String)

    // extraBufferCapacity so tryEmit never drops a relearn under a momentary collector stall;
    // replay = 0 (default) because a relearn only matters live — a late subscriber must not
    // re-apply a stale one to a different episode.
    private val mutableEvents = MutableSharedFlow<Relearn>(extraBufferCapacity = 8)
    val events: SharedFlow<Relearn> = mutableEvents.asSharedFlow()

    /** Non-blocking publish from the mark handler's IO coroutine. */
    fun publish(sampodId: String, sidecarJson: String) {
        mutableEvents.tryEmit(Relearn(sampodId, sidecarJson))
    }
}
