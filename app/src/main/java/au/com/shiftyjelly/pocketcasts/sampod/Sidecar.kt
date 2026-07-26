package au.com.shiftyjelly.pocketcasts.sampod

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * SamPod skip-sidecar — the server-detected ad windows for one episode.
 *
 * Produced by the SamPod ingest server (queue-time detect on the cached bytes) and served
 * at `/sampod/sidecar/<id>`. Because the app plays the server's CACHED audio
 * (via BaseEpisode.overrideStreamUrl → `/sampod/audio/<id>`), these start/end timestamps
 * match the played bytes exactly — this is what dissolves DAI (design doc §Architecture).
 *
 * Uses Moshi (already a Pocket Casts dependency). Field names mirror the server JSON.
 */
@JsonClass(generateAdapter = true)
data class Sidecar(
    @Json(name = "id") val id: String,
    @Json(name = "episode_title") val episodeTitle: String = "",
    @Json(name = "feed_title") val feedTitle: String = "",
    @Json(name = "audio_url") val audioUrl: String = "", // server-relative, e.g. /sampod/audio/<id>
    @Json(name = "duration_s") val durationS: Double = 0.0,
    @Json(name = "skips") val skips: List<AdSkip> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AdSkip(
    @Json(name = "start_s") val startS: Double,
    @Json(name = "end_s") val endS: Double,
    @Json(name = "advertiser") val advertiser: String = "ad",
    @Json(name = "type") val type: String = "sponsor",
    @Json(name = "confidence") val confidence: Double = 1.0,
) {
    val startMs: Int get() = (startS * 1000).toInt()
    val endMs: Int get() = (endS * 1000).toInt()
}
