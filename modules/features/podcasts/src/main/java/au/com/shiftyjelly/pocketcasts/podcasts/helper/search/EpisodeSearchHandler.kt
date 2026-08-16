package au.com.shiftyjelly.pocketcasts.podcasts.helper.search

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.PodcastScreenSearchClearedEvent
import com.automattic.eventhorizon.PodcastScreenSearchPerformedEvent
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class EpisodeSearchHandler @Inject constructor(
    settings: Settings,
    private val podcastManager: PodcastManager,
    private val episodeManager: EpisodeManager,
    private val eventHorizon: EventHorizon,
) : SearchHandler<BaseEpisode>() {
    private val searchDebounce = settings.getEpisodeSearchDebounceMs()

    override fun getSearchResultsObservable(podcastUuid: String): Observable<SearchResult> = searchQueryRelay.debounce {
        // Only debounce when search has a value otherwise it slows down loading the pages
        if (it.isEmpty()) {
            Observable.empty()
        } else {
            Observable.timer(searchDebounce, TimeUnit.MILLISECONDS)
        }
    }.switchMapSingle { searchTerm ->
        if (searchTerm.length > 1) {
            // Local-only search: match against episodes already stored in the DB (archived included),
            // rather than the server-side /mobile/podcast/episode/search endpoint. The server index does
            // not reliably return older/archived episodes, so a fully-archived back-catalog searched
            // empty even though every episode exists locally. Matching titles in the local DB fixes that
            // and works offline. (Sam fork — 2026-08-16)
            searchEpisodesLocally(podcastUuid, searchTerm)
                .map { SearchResult(searchTerm, it) }
                .onErrorReturnItem(noSearchResult)
        } else {
            Single.just(noSearchResult)
        }
    }.distinctUntilChanged()

    private fun searchEpisodesLocally(podcastUuid: String, searchTerm: String): Single<List<String>> =
        Single.fromCallable {
            val podcast = podcastManager.findPodcastByUuidBlocking(podcastUuid)
                ?: return@fromCallable emptyList<String>()
            episodeManager.findEpisodesByPodcastOrderedByPublishDateBlocking(podcast)
                .filter { it.title.contains(searchTerm, ignoreCase = true) }
                .map { it.uuid }
        }.subscribeOn(Schedulers.io())

    override fun trackSearchIfNeeded(oldValue: String, newValue: String) {
        val event = if (oldValue.isEmpty() && newValue.isNotEmpty()) {
            PodcastScreenSearchPerformedEvent
        } else if (oldValue.isNotEmpty() && newValue.isEmpty()) {
            PodcastScreenSearchClearedEvent
        } else {
            null
        }
        event?.let(eventHorizon::track)
    }
}
