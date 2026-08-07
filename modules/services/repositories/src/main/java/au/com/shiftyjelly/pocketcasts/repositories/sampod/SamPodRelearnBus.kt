package au.com.shiftyjelly.pocketcasts.repositories.sampod

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-module signal that a mark-a-miss correction was SUBMITTED for an episode.
 *
 * The mark button lives in the player FEATURE module (PlayerViewModel); the skip engine lives
 * in the APP module (SamPodSkipCoordinator). Neither can reference the other, but both depend
 * on `repositories` — so this object is the seam between them.
 *
 * It carries only the episode's sampod-id, NOT a sidecar. The server-side relearn runs an LLM
 * (~35-75s, longer than the mark POST's HTTP timeout) but writes a provisional skip almost
 * immediately, so the coordinator responds to this signal by POLLING the sidecar and adopting
 * the result live — first the provisional window (seconds), then the LLM-refined one (~a
 * minute). Decoupling from the POST response is deliberate: the mark POST can even time out and
 * the correction still lands, because the server processes it regardless (SamPod #6b, 2026-08-07).
 */
object SamPodRelearnBus {
    data class Relearn(val sampodId: String)

    // extraBufferCapacity so tryEmit never drops a submit under a momentary collector stall;
    // replay = 0 because a submit only matters live — a late subscriber must not re-poll for
    // a correction that already settled on a since-changed episode.
    private val mutableEvents = MutableSharedFlow<Relearn>(extraBufferCapacity = 8)
    val events: SharedFlow<Relearn> = mutableEvents.asSharedFlow()

    /** Non-blocking publish from the mark handler. Fired when the mark is submitted. */
    fun publish(sampodId: String) {
        mutableEvents.tryEmit(Relearn(sampodId))
    }
}
