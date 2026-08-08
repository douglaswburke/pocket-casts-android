package au.com.shiftyjelly.pocketcasts.repositories.sampod

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent `episodeUuid -> processed-audio-URL` map so a WorkManager download can fetch the
 * SamPod CACHED COPY (byte-exact with the ad-skip sidecar) instead of the ad-laden canonical
 * enclosure. This is the mechanism behind SamPod #6a (offline download): once those bytes are
 * downloaded to the episode's normal file, `EpisodeLocation.create` plays the local file and the
 * sidecar's ad windows line up exactly — offline, no tailnet dependency in the truck.
 *
 * Why a persistent store and NOT `BaseEpisode.overrideStreamUrl`:
 *   - `overrideStreamUrl` is `@Ignore` (in-memory only, never a Room column), and a download
 *     worker can run in a FRESH PROCESS after the app that set it has died. The mapping the
 *     worker needs must survive to disk.
 *   - SharedPreferences, not a new Room column, deliberately — a fork should not carry a 123rd
 *     schema migration for this.
 *
 * Why it does NOT overwrite `download_url`: the sidecar id is `sha1(downloadUrl)`, and the server
 * keys analysis by the real enclosure URL. Keeping the DB's `download_url` as the true enclosure
 * (and only substituting the fetch URL inside the download worker) means the sidecar lookup and
 * any future re-analysis stay correct.
 *
 * Fails silent + open, matching [SamPodAnalyzed]: if [init] was never called, [get] returns null
 * and the download simply uses the normal enclosure URL — never a crash, and never wrong bytes.
 */
object SamPodOverrideStore {
    private const val PREFS = "sampod_override_urls"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Prime the store once from `PocketCastsApplication.onCreate`. Android always creates the
     * Application before any WorkManager worker in the same process, so a worker's [get] will
     * see whatever the app registered in this process — and SharedPreferences carries anything
     * registered in a prior process across a restart.
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** Register the processed cached-copy URL the download worker should fetch for [uuid]. */
    fun put(uuid: String, processedUrl: String) {
        prefs?.edit()?.putString(uuid, processedUrl)?.apply()
    }

    /** The processed cached-copy URL for [uuid], or null if none registered / not initialised. */
    fun get(uuid: String): String? = prefs?.getString(uuid, null)

    /** Forget the mapping for [uuid] (e.g. if the download is abandoned). */
    fun remove(uuid: String) {
        prefs?.edit()?.remove(uuid)?.apply()
    }
}
