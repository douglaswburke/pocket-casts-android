package au.com.shiftyjelly.pocketcasts.repositories.sampod

import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import au.com.shiftyjelly.pocketcasts.repositories.BuildConfig
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Which episodes SamPod has already analyzed, for the "ad-skip ready" badge in episode lists.
 *
 * Doug's question on 2026-07-31: 23 episodes were analyzed and the app showed no sign of any
 * of them. You found out by queueing something and seeing whether ads got skipped.
 *
 * Design constraint that shapes everything here: the badge is decided during a RecyclerView
 * bind, on the main thread, once per visible row. So there is NO per-row network call. The
 * server exposes one lean id set (/sampod/analyzed, ~500 bytes for 23 episodes); this object
 * caches it and the badge becomes a local set lookup.
 *
 * Lives in `repositories` because BOTH row renderers need it: the podcast episode list
 * (podcasts module) and Up Next (player module) are separate ViewHolders in separate feature
 * modules, and feature modules cannot see each other. Putting it in podcasts first meant the
 * badge appeared in one list and not the other — which is exactly what Doug hit (2026-08-01).
 *
 * Deliberately dependency-free — no Hilt, no injection, no Application wiring. Every extra
 * touch point is another thing that can fail to compile on a machine with no JVM to check it.
 *
 * Fails silent and open: unreachable server, blank config, or a not-yet-loaded cache all mean
 * "no badge", never a crash and never a wrong badge.
 *
 * 2026-09-14 (Doug's 05:15 flag — Up Next showed no badges, then all of them "now"): the
 * server log proved the phone fetched four sidecars fine at 05:13:06 but never asked for
 * /sampod/analyzed at all. Across the prior 10 days, 6 of 19 app launches had the same
 * signature. The first outbound call at app start was failing (the tailnet route is not
 * always up in the first moments after launch), and two things then compounded it:
 *   1. a failure backed off a FULL TTL (5 min) — so the very fetch that was most likely to
 *      fail was also the one retried the slowest; and
 *   2. when the set finally arrived nothing re-bound the already-drawn rows, so the badge
 *      only appeared after Doug left the screen and came back.
 * Fix: a failed fetch retries at 3s / 10s / 30s and backs off only 20s after that, and any
 * change to the set notifies registered adapters (Up Next, podcast list) to re-bind.
 */
object SamPodAnalyzed {
    /** Adapters register one of these to re-bind rows when the analyzed set changes. */
    fun interface Listener {
        fun onAnalyzedSetChanged()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var ids: Set<String> = emptySet()
    @Volatile private var loadedAtMs = 0L

    private const val TTL_MS = 5 * 60 * 1000L

    /** After the retry chain is exhausted, how long a bind waits before kicking a new fetch. */
    private const val FAIL_BACKOFF_MS = 20 * 1000L

    /** Quick retries after a failed fetch — covers a tailnet route that is a few seconds late. */
    private val RETRY_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val configured: Boolean
        get() = BuildConfig.SAMPOD_SERVER.isNotBlank() && BuildConfig.SAMPOD_TOKEN.isNotBlank()

    /**
     * Prime the cache at app start.
     *
     * Without this the first list you open shows NO badges: isAnalyzed() kicks the refresh but
     * returns false immediately, rows bind once, and nothing re-binds them when the set lands.
     * Doug hit exactly that — Up Next was bare until he visited Podcasts and came back, which
     * warmed the cache and forced a rebind (2026-08-01). Warming at startup means the set is
     * there before any list exists.
     */
    fun warm() {
        if (configured) maybeRefresh()
    }

    /** Called on the main thread whenever the analyzed set changes. Safe to call repeatedly. */
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Green, so the marker reads at a glance instead of blending into the date line. */
    private val BADGE_COLOR = 0xFF4CAF50.toInt()   // Material Green 500 — not `const`: .toInt() is a call

    /**
     * The episode-row summary with " · ✓ ad-skip" appended in green when analyzed.
     *
     * Lives here rather than in each ViewHolder so the two renderers (podcast list, Up Next)
     * cannot drift in wording or colour — they already drifted once by having the badge in
     * only one of them.
     */
    fun badge(summary: CharSequence, downloadUrl: String?): CharSequence {
        if (!isAnalyzed(downloadUrl)) return summary
        val out = SpannableStringBuilder(summary)
        val from = out.length
        out.append(" · ✓ ad-skip")
        out.setSpan(ForegroundColorSpan(BADGE_COLOR), from, out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /**
     * True when this episode has a SamPod sidecar. Cheap and non-blocking — a set lookup,
     * plus a background refresh kick when the cache is cold or stale.
     *
     * Returns false while the first fetch is still in flight, so a freshly-opened list may
     * badge nothing for a moment; the registered adapters re-bind when the set lands. That is
     * the right trade: a missing badge is a small cosmetic miss, whereas blocking a bind on
     * the network would jank the scroll.
     */
    fun isAnalyzed(downloadUrl: String?): Boolean {
        if (!configured || downloadUrl.isNullOrBlank()) return false
        maybeRefresh()
        return sampodId(downloadUrl) in ids
    }

    private fun maybeRefresh() {
        if (System.currentTimeMillis() - loadedAtMs < TTL_MS) return
        if (!refreshing.compareAndSet(false, true)) return // one refresh in flight, not one per row
        scope.launch { refresh(attempt = 0) }
    }

    /**
     * One fetch of /sampod/analyzed. Runs with `refreshing` held and releases it on exit.
     * On failure, schedules the next attempt from RETRY_DELAYS_MS (which re-acquires the flag).
     */
    private suspend fun refresh(attempt: Int) {
        try {
            val url = "${BuildConfig.SAMPOD_SERVER.trimEnd('/')}/sampod/analyzed"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("k", BuildConfig.SAMPOD_TOKEN)
                ?.build()
                ?.toString()
            if (url == null) {
                loadedAtMs = System.currentTimeMillis() // don't hammer a malformed base url
                return
            }
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val arr = JSONObject(resp.body.string()).optJSONArray("ids")
                    ?: throw IOException("no ids in response")
                val next = HashSet<String>(arr.length())
                for (i in 0 until arr.length()) next.add(arr.getString(i))
                val changed = next != ids
                ids = next
                android.util.Log.i("SamPod", "analyzed set refreshed: ${next.size} episode(s)" +
                    if (changed) " (changed — re-binding lists)" else "")
                if (changed) notifyChanged()
            }
            loadedAtMs = System.currentTimeMillis()
        } catch (e: Exception) {
            // Off the tailnet is the normal case, not an error worth shouting about — but a
            // launch-time miss was silently costing the badge for 5 minutes, so retry fast and
            // back off short. Log at WARN with the message so a logcat read can tell a
            // "route not up yet" from a "server down".
            loadedAtMs = System.currentTimeMillis() - TTL_MS + FAIL_BACKOFF_MS
            android.util.Log.w("SamPod", "analyzed refresh failed (attempt $attempt): " +
                "${e.javaClass.simpleName}: ${e.message}")
            if (attempt < RETRY_DELAYS_MS.size) {
                val delayMs = RETRY_DELAYS_MS[attempt]
                scope.launch {
                    delay(delayMs)
                    if (refreshing.compareAndSet(false, true)) refresh(attempt + 1)
                }
            }
        } finally {
            refreshing.set(false)
        }
    }

    private fun notifyChanged() {
        mainHandler.post {
            for (listener in listeners) listener.onAnalyzedSetChanged()
        }
    }

    /** Must match canon.eid_for() on the server: sha1(audio_url) truncated to 16 hex chars. */
    private fun sampodId(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(16)
}
