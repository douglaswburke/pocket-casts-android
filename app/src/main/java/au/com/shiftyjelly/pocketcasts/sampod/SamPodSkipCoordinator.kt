package au.com.shiftyjelly.pocketcasts.sampod

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import com.squareup.moshi.Moshi
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
    moshi: Moshi,
) {
    private val api = SamPodApi(baseUrl = serverUrl.trimEnd('/'), token = token, moshi = moshi)
    private val controller = AdSkipController()
    private var currentEpisodeUuid: String? = null
    private var sidecar: Sidecar? = null

    fun start() {
        if (serverUrl.isBlank() || token.isBlank()) return
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
        }
        val skips = sidecar?.skips ?: return
        val decision = controller.check(state.positionMs, skips) ?: return
        playbackManager.seekToTimeMs(decision.seekToMs)
    }

    private suspend fun loadSidecar(): Sidecar? = withContext(Dispatchers.IO) {
        val url = playbackManager.getCurrentEpisode()?.downloadUrl ?: return@withContext null
        api.fetchSidecar(sha1Id(url))
    }

    private fun sha1Id(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(16)
}
