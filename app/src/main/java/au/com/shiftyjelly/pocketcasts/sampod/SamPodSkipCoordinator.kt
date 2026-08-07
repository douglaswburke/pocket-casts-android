package au.com.shiftyjelly.pocketcasts.sampod

import android.util.Log
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.sampod.SamPodRelearnBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
 *
 * Increment 4 (2026-07-30): AUTO-QUEUE. Adding an episode to Up Next now asks the server to
 * analyze it if it hasn't been already, instead of silently giving up — "hit queue" is the
 * one gesture that starts the whole SamPod pipeline. Most episodes are already analyzed by
 * the nightly ingest, so this normally costs nothing; the server's daily spend cap bounds
 * the cases where it doesn't.
 */
class SamPodSkipCoordinator(
    private val playbackManager: PlaybackManager,
    private val scope: CoroutineScope,
    private val serverUrl: String,
    private val token: String,
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager? = null,
    private val autoQueue: Boolean = true,
) {
    private val api = SamPodApi(baseUrl = serverUrl.trimEnd('/'), token = token)
    private val controller = AdSkipController()
    // @Volatile: an analysis poller on one IO thread may adopt a just-ready sidecar while
    // the playback collector reads these on another.
    @Volatile private var currentEpisodeUuid: String? = null
    // The sampod id (sha1(downloadUrl)[:16]) of the current episode — matched against relearn
    // events so a mark on episode A never swaps the sidecar while episode B is playing.
    @Volatile private var currentSampodId: String? = null

    @Volatile private var sidecar: Sidecar? = null
    // Concurrent sets, not plain ones: prepareQueue() runs on the IO dispatcher and each
    // analysis poller is its own IO coroutine, so both are touched from several threads.
    private val prepared: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Episodes we've asked the server to analyze, so one queue-add starts one poller. */
    private val awaitingAnalysis: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        // Increment 3 (P2): set the cached-copy override for QUEUED episodes before they play,
        // so ad-skip works from the first play with NO stop+replay dance.
        scope.launch {
            playbackManager.upNextQueue.changesObservable.asFlow().collect {
                prepareQueue()
            }
        }
        // Increment 6b (2026-08-06): a mark-a-miss relearn returns the fresh sidecar; adopt it
        // live if it's for the episode playing now, so the correction takes effect immediately
        // instead of waiting for the next episode-open re-fetch (the read-once bug).
        scope.launch {
            SamPodRelearnBus.events.collect { onRelearned(it) }
        }
    }

    /** For every episode in Up Next that has a server sidecar, point it at the cached copy
     *  (once). Episodes with no sidecar are handed to the server for analysis. Runs off-main
     *  on each queue change. */
    private suspend fun prepareQueue() = withContext(Dispatchers.IO) {
        for (episode in playbackManager.upNextQueue.allEpisodes) {
            if (episode !is PodcastEpisode) continue
            if (episode.uuid in prepared) continue
            if (episode.overrideStreamUrl != null) {
                prepared.add(episode.uuid) // already wired up; nothing left to do
                continue
            }
            val url = episode.downloadUrl ?: continue
            val id = sha1Id(url)
            val sc = api.fetchSidecar(id)
            if (sc != null) {
                prepared.add(episode.uuid) // resolved — don't look at it again
                attachCachedCopy(episode, id, sc)
            } else if (autoQueue) {
                // The gap this increment closes: before, a missing sidecar ended here and the
                // episode was simply un-skippable forever. Deliberately NOT added to
                // `prepared` — analysis takes minutes, and marking it done would mean never
                // re-checking. `awaitingAnalysis` is what keeps this to one poller each.
                requestAnalysis(episode, id, url)
            }
        }
    }

    private suspend fun attachCachedCopy(episode: PodcastEpisode, id: String, sc: Sidecar) {
        val cached = api.cachedAudioUrl(id) ?: return
        episode.overrideStreamUrl = cached
        episodeManager.update(episode)
        Log.i(TAG, "queue-prep: overrideStreamUrl set for ${episode.uuid} (${sc.skips.size} ads)")
    }

    /**
     * Ask the server to analyze a queued episode, then poll until its sidecar appears.
     *
     * Backoff rather than a fixed interval because processing time scales with episode
     * length — a 20-minute show is ready in ~2 min, a 2-hour Ferriss episode takes ~10 —
     * and a fixed short poll would just hammer the tailnet for the long ones. Gives up after
     * ~30 min of wall-clock; the next Up Next change re-checks from scratch, and the episode
     * simply plays with ads in the meantime rather than failing.
     */
    private fun requestAnalysis(episode: PodcastEpisode, id: String, url: String) {
        if (!awaitingAnalysis.add(episode.uuid)) return // a poller is already on this one
        scope.launch(Dispatchers.IO) {
            try {
                val feed = podcastManager?.findPodcastByUuid(episode.podcastUuid)?.title ?: ""
                val status = api.queueEpisode(url, episode.title, feed)
                if (status == null) {
                    Log.w(TAG, "auto-queue: server unreachable for ${episode.title.take(40)}")
                    return@launch
                }
                Log.i(TAG, "auto-queue: '${episode.title.take(40)}' -> $status")
                for (waitMs in POLL_BACKOFF_MS) {
                    delay(waitMs)
                    val sc = api.fetchSidecar(id) ?: continue
                    // `add` returns false if prepareQueue() already claimed this episode —
                    // it re-scans on every Up Next change and can find the finished sidecar
                    // seconds before the poller's next tick, which wrote the same override
                    // twice (observed in the 2026-07-30 first live run: harmless, identical
                    // value, but a redundant DB write). Whoever claims it does the attach.
                    if (!prepared.add(episode.uuid)) {
                        Log.i(TAG, "auto-queue: ready — already attached by queue-prep")
                        return@launch
                    }
                    // Re-read the episode before writing. The copy captured when this poller
                    // started can be many minutes stale by now — Doug may have listened to
                    // part of it meanwhile — and persisting the old row would roll back
                    // playedUpTo. Only the fresh entity is safe to update.
                    val fresh = episodeManager.findEpisodeByUuid(episode.uuid) as? PodcastEpisode
                    if (fresh == null) {
                        Log.w(TAG, "auto-queue: episode ${episode.uuid} vanished before attach")
                        return@launch
                    }
                    attachCachedCopy(fresh, id, sc)
                    Log.i(TAG, "auto-queue: ready — ${sc.skips.size} ad(s) in '${episode.title.take(40)}'")
                    // If this is the episode playing right now, adopt the sidecar live so the
                    // skips take effect without waiting for the next episode change.
                    if (currentEpisodeUuid == episode.uuid) {
                        sidecar = sc
                        controller.reset()
                    }
                    return@launch
                }
                Log.w(TAG, "auto-queue: gave up waiting on '${episode.title.take(40)}' " +
                    "(still processing, deferred by the spend cap, or failed server-side)")
            } catch (e: Exception) {
                Log.w(TAG, "auto-queue FAILED: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                awaitingAnalysis.remove(episode.uuid)
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

    /**
     * A mark-a-miss was SUBMITTED for an episode (signalled over [SamPodRelearnBus] from the
     * player module). If it is for the episode playing now, poll the sidecar and adopt the
     * result live. Ignored if it is for a different episode than the one currently playing.
     */
    private fun onRelearned(event: SamPodRelearnBus.Relearn) {
        val id = event.sampodId
        if (id != currentSampodId) {
            Log.i(TAG, "relearn signal for $id ignored (current=$currentSampodId)")
            return
        }
        scope.launch { pollAndAdopt(id) }
    }

    /**
     * The server's mark-a-miss relearn runs an LLM (~35-75s) but writes a provisional skip
     * almost immediately. Poll the sidecar and adopt each time the skip-set changes — catching
     * first the provisional window (seconds) then the LLM-refined one (~a minute) — so the
     * correction takes effect on the playing episode with no re-open. Bounded (~90s); stops
     * early if the episode changes under us.
     */
    private suspend fun pollAndAdopt(id: String) {
        var lastSig = sidecar.skipSignature()
        repeat(12) {   // 12 * 8s ≈ 96s — covers the slowest observed relearn
            delay(8_000)
            if (id != currentSampodId) return   // episode changed — abandon this poll
            val fresh = withContext(Dispatchers.IO) { api.fetchSidecar(id) } ?: return@repeat
            val sig = fresh.skipSignature()
            if (sig != lastSig) {
                sidecar = fresh
                controller.reset()
                lastSig = sig
                Log.i(TAG, "relearn adopted live (poll): ${fresh.skips.size} ad(s) for $currentEpisodeUuid")
            }
        }
    }

    /** A change-detection fingerprint of a sidecar's ad windows (order-independent). */
    private fun Sidecar?.skipSignature(): String =
        this?.skips?.sortedBy { it.startMs }?.joinToString(",") { "${it.startMs}-${it.endMs}" } ?: ""

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
        currentSampodId = id
        Log.i(TAG, "loadSidecar id=$id url=${url.take(90)}")
        val sc = api.fetchSidecar(id) ?: return@withContext null

        // Increment 2: point playback at the SERVER'S CACHED copy so the sidecar timestamps
        // match the played bytes exactly (dissolves DAI — the live stream stitches ads at
        // different offsets). Persisted; takes effect on the NEXT play of this episode.
        val cached = api.cachedAudioUrl(id)
        if (cached != null && episode is PodcastEpisode && episode.overrideStreamUrl != cached) {
            episode.overrideStreamUrl = cached
            episodeManager.update(episode)
            Log.i(TAG, "set overrideStreamUrl -> cached copy (play-time fallback; queue-prep normally does this first)")
        }
        sc
    }

    private companion object {
        const val TAG = "SamPod"

        /** ~30 min total: quick early checks for short shows, patient later ones for long. */
        val POLL_BACKOFF_MS = longArrayOf(
            30_000, 30_000, 60_000, 60_000, 120_000, 120_000,
            300_000, 300_000, 300_000, 300_000,
        )
    }

    private fun sha1Id(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(16)
}
