package au.com.shiftyjelly.pocketcasts.sampod

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the SamPod ingest server (tailnet-hosted, token-gated). Fetches the
 * skip-sidecar for a queued episode and builds the cached-audio URL that the player can
 * stream (BaseEpisode.overrideStreamUrl, increment 2) so the played bytes match the sidecar.
 *
 * Parses with org.json (built into Android) — no Moshi, no codegen (see Sidecar.kt).
 * Base URL + token come from BuildConfig (local.properties, gitignored).
 */
class SamPodApi(
    private val baseUrl: String,   // e.g. http://<mini-tailnet>:8848  (no trailing slash)
    private val token: String,     // ?k= gate
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    /** GET /sampod/sidecar/<id> → Sidecar, or null on 404 / error. Blocking; call off-main. */
    fun fetchSidecar(id: String): Sidecar? {
        val url = "$baseUrl/sampod/sidecar/$id".withToken() ?: return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                android.util.Log.i("SamPod", "fetchSidecar http ${resp.code} for id=$id")
                if (!resp.isSuccessful) return null
                parse(resp.body.string(), id)
            }
        } catch (e: Exception) {
            android.util.Log.w("SamPod", "fetchSidecar FAILED id=$id: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** The playback URL for the server's cached copy → set as episode.overrideStreamUrl. */
    fun cachedAudioUrl(id: String): String? = "$baseUrl/sampod/audio/$id".withToken()

    private fun parse(json: String, fallbackId: String): Sidecar? = try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("skips")
        val skips = ArrayList<AdSkip>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                skips.add(
                    AdSkip(
                        startS = s.optDouble("start_s", 0.0),
                        endS = s.optDouble("end_s", 0.0),
                        advertiser = s.optString("advertiser", "ad"),
                        type = s.optString("type", "sponsor"),
                        confidence = s.optDouble("confidence", 1.0),
                    ),
                )
            }
        }
        Sidecar(
            id = o.optString("id", fallbackId),
            episodeTitle = o.optString("episode_title", ""),
            feedTitle = o.optString("feed_title", ""),
            audioUrl = o.optString("audio_url", ""),
            durationS = o.optDouble("duration_s", 0.0),
            skips = skips,
        )
    } catch (e: Exception) {
        null
    }

    private fun String.withToken(): String? {
        val http = this.toHttpUrlOrNull() ?: return null
        return http.newBuilder().addQueryParameter("k", token).build().toString()
    }
}
