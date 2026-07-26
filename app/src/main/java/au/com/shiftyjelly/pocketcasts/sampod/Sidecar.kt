package au.com.shiftyjelly.pocketcasts.sampod

/**
 * SamPod skip-sidecar — the server-detected ad windows for one episode
 * (served at /sampod/sidecar/<id>). Plain data classes, parsed manually with org.json in
 * SamPodApi — deliberately NO Moshi: the app module doesn't run Moshi codegen on this
 * package, so a generated adapter never exists and moshi.adapter() crashes at launch
 * (fixed 2026-07-26). org.json is built into Android — zero dependency, no codegen.
 */
data class Sidecar(
    val id: String,
    val episodeTitle: String = "",
    val feedTitle: String = "",
    val audioUrl: String = "",
    val durationS: Double = 0.0,
    val skips: List<AdSkip> = emptyList(),
)

data class AdSkip(
    val startS: Double,
    val endS: Double,
    val advertiser: String = "ad",
    val type: String = "sponsor",
    val confidence: Double = 1.0,
) {
    val startMs: Int get() = (startS * 1000).toInt()
    val endMs: Int get() = (endS * 1000).toInt()
}
