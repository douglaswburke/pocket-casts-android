package au.com.shiftyjelly.pocketcasts.sampod

import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin client for the SamPod ingest server (tailnet-hosted, token-gated). Fetches the
 * skip-sidecar for a queued episode and builds the cached-audio URL that the player should
 * stream (set as BaseEpisode.overrideStreamUrl so the played bytes match the sidecar).
 *
 * Uses OkHttp + Moshi (both already Pocket Casts deps). Base URL + token come from
 * BuildConfig (populated from local.properties, gitignored) — see SAMPOD_INTEGRATION.md.
 * The server id for an episode = the SamPod queue id (server derives it from the enclosure
 * URL as sha1[:16]); the app can also resolve by asking the server, but the simplest path is
 * to queue via the server and store the returned id on the episode.
 *
 * ⚠️ Not yet compile-verified (authored on a headless host). Kept dependency-light and
 * self-contained; if the `app` module lacks okhttp/moshi transitively, add them (they exist
 * elsewhere in the tree). See the integration doc.
 */
class SamPodApi(
    private val baseUrl: String,   // e.g. https://<mini-tailnet>:8848  (no trailing slash)
    private val token: String,     // ?k= gate
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    moshi: Moshi = Moshi.Builder().build(),
) {
    private val sidecarAdapter = moshi.adapter(Sidecar::class.java)

    /** GET /sampod/sidecar/<id> → Sidecar, or null on 404 / error. Blocking; call off-main. */
    fun fetchSidecar(id: String): Sidecar? {
        val url = "$baseUrl/sampod/sidecar/$id".withToken() ?: return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                // OkHttp 5: ResponseBody is non-null → no safe-call (warnings-as-errors).
                sidecarAdapter.fromJson(resp.body.string())
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The playback URL for the server's cached copy → set as episode.overrideStreamUrl. */
    fun cachedAudioUrl(id: String): String? =
        "$baseUrl/sampod/audio/$id".withToken()

    private fun String.withToken(): String? {
        val http = this.toHttpUrlOrNull() ?: return null
        return http.newBuilder().addQueryParameter("k", token).build().toString()
    }
}
