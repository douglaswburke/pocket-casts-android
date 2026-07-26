package au.com.shiftyjelly.pocketcasts.sampod

import android.util.Log
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * SamPod ad-skip — wired into the Pocket Casts player WITHOUT editing PlaybackManager.
 *
 * Started once from PocketCastsApplication. It observes playbackStateFlow, loads the SamPod
 * server's skip-sidecar for the current episode (id = sha1(downloadUrl)[:16], matching how the
 * server keys episodes), and seeks past ad windows via seekToTimeMs. Confidence gate + re-fire
 * guard live in AdSkipController. No-op if the server/token BuildConfig fields are blank.
 *
 * Increment 1 (2026-07-26): skip only. Works on static-ad episodes where the enclosure bytes ==
 * the bytes the detector analyzed. Increment 2 adds BaseEpisode.overrideStreamUrl (play the
 * server's cached copy) so DAI episodes match too, and the timeline ad-bars + ±10/30 UI.
 */
class SamPodSkipCoordinator(
    private val playbackManager: PlaybackManager,
    private val scope: CoroutineScope,
    private val serverUrl: String,
    private val token: String,
    private val episodeManager: EpisodeManager,
) {
    private val api = SamPodApi(baseUrl = serverUrl.trimEnd('/'), token = token)
    private val controller = AdSkipController()
    private var currentEpisodeUuid: String? = null
    private var sidecar: Sidecar? = null

    fun start() {
        if (serverUrl.isBlank() || token.isBlank()) {
            Log.w(TAG, "not configured (server/token blank) — ad-skip off")
            return
        }
        Log.i(TAG, "started; server=$serverUrl")
        scope.launch {
            playbackManager.playbackStateFlow.collect { state ->
                onState(state)
            }
        }
    }

    private suspend fun onState(state: PlaybackState) {
        val uuid = state.episodeUuid
        if (uuid.isBlank()) return
        if (uuid != currentEpisodeUuid) {
            currentEpisodeUuid = uuid
            controller.reset()
            sidecar = loadSidecar()
            Log.i(TAG, "episode=$uuid sidecar=${sidecar?.let { "${it.skips.size} ads" } ?: "NOT FOUND"}")
        }
        val skips = sidecar?.skips ?: return
        val decision = controller.check(state.positionMs, skips) ?: return
        Log.i(TAG, "SKIP ${decision.ad.advertiser} at ${state.positionMs}ms -> seek ${decision.seekToMs}ms")
        playbackManager.seekToTimeMs(decision.seekToMs)
    }

    private suspend fun loadSidecar(): Sidecar? = withContext(Dispatchers.IO) {
        val episode = playbackManager.getCurrentEpisode() ?: run {
            Log.w(TAG, "no current episode")
            return@withContext null
        }
        val url = episode.downloadUrl ?: run {
            Log.w(TAG, "no downloadUrl for current episode")
            return@withContext null
        }
        val id = sha1Id(url)
        Log.i(TAG, "loadSidecar id=$id url=${url.take(90)}")
        val sc = api.fetchSidecar(id) ?: return@withContext null

        // Increment 2: point playback at the SERVER'S CACHED copy so the sidecar timestamps
        // match the played bytes exactly (dissolves DAI — the live stream stitches ads at
        // different offsets). Persisted; takes effect on the NEXT play of this episode.
        val cached = api.cachedAudioUrl(id)
        if (cached != null && episode is PodcastEpisode && episode.overrideStreamUrl != cached) {
            episode.overrideStreamUrl = cached
            episodeManager.update(episode)
            Log.i(TAG, "set overrideStreamUrl -> cached copy (STOP + REPLAY to apply)")
        }
        sc
    }

    private companion object {
        const val TAG = "SamPod"
    }

    private fun sha1Id(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(16)
}
