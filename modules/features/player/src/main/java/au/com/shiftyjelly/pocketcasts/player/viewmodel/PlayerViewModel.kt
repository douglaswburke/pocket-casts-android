package au.com.shiftyjelly.pocketcasts.player.viewmodel

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.toLiveData
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.compose.PodcastColors
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.BlazeAd
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode
import au.com.shiftyjelly.pocketcasts.models.to.Chapter
import au.com.shiftyjelly.pocketcasts.models.to.ChapterSummaryData
import au.com.shiftyjelly.pocketcasts.models.to.Chapters
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.models.to.toChapterOriginType
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeViewSource
import au.com.shiftyjelly.pocketcasts.player.view.UpNextPlaying
import au.com.shiftyjelly.pocketcasts.player.view.bookmark.BookmarkArguments
import au.com.shiftyjelly.pocketcasts.player.view.dialog.ClearUpNextDialog
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.model.ArtworkConfiguration
import au.com.shiftyjelly.pocketcasts.repositories.ads.BlazeAdsManager
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.di.IoDispatcher
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadQueue
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadType
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackNoticeManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimer
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimerState
import au.com.shiftyjelly.pocketcasts.repositories.playback.StreamVideoState
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextSource
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.sampod.SamPodRelearnBus
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.automattic.eventhorizon.BannerAdImpressionEvent
import com.automattic.eventhorizon.BannerAdTappedEvent
import com.automattic.eventhorizon.EpisodeArchivedEvent
import com.automattic.eventhorizon.EpisodeDetailPodcastNameTappedEvent
import com.automattic.eventhorizon.EpisodeMarkedAsPlayedEvent
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.PlaybackContentType
import com.automattic.eventhorizon.PlaybackEffectSettingsChangedEvent
import com.automattic.eventhorizon.PlayerNextChapterTappedEvent
import com.automattic.eventhorizon.PlayerPreviousChapterTappedEvent
import com.automattic.eventhorizon.SettingType
import com.automattic.eventhorizon.Trackable
import com.jakewharton.rxrelay2.BehaviorRelay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asObservable
import kotlinx.coroutines.withContext
import android.widget.Toast
import au.com.shiftyjelly.pocketcasts.player.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import timber.log.Timber
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val playbackNoticeManager: PlaybackNoticeManager,
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val bookmarkManager: BookmarkManager,
    private val downloadQueue: DownloadQueue,
    private val sleepTimer: SleepTimer,
    private val settings: Settings,
    private val theme: Theme,
    private val eventHorizon: EventHorizon,
    blazeAdsManager: BlazeAdsManager,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel(),
    CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    data class PodcastEffectsData(val podcast: Podcast, val effects: PlaybackEffects, val showCustomEffectsSettings: Boolean = true)
    data class PlayerHeader(
        val positionMs: Int = 0,
        val durationMs: Int = -1,
        val isPlaying: Boolean = false,
        val isPrepared: Boolean = false,
        val episode: BaseEpisode? = null,
        val streamVideoState: StreamVideoState = StreamVideoState.NotVideo,
        val videoRenderingEnabled: Boolean = true,
        val podcastTitle: String? = null,
        val isPlaybackRemote: Boolean = false,
        val chapters: Chapters = Chapters(),
        val backgroundColor: Int = 0xFF000000.toInt(),
        val iconTintColor: Int = 0xFFFFFFFF.toInt(),
        val skipForwardInSecs: Int = 15,
        val skipBackwardInSecs: Int = 30,
        val isSleepRunning: Boolean = false,
        val isEffectsOn: Boolean = false,
        val adjustRemainingTimeDuration: Boolean = false,
        val playbackEffects: PlaybackEffects = PlaybackEffects(),
        val isBuffering: Boolean = false,
        val bufferedUpToMs: Int = 0,
        val theme: Theme.ThemeType = Theme.ThemeType.DARK,
        val useEpisodeArtwork: Boolean = false,
    ) {
        val podcastUuid = (episode as? PodcastEpisode)?.podcastUuid
        val episodeUuid = episode?.uuid.orEmpty()
        val episodeTitle = episode?.title.orEmpty()

        val isVideo = videoRenderingEnabled && when (streamVideoState) {
            StreamVideoState.HasVideo -> true
            StreamVideoState.Unknown, StreamVideoState.AudioOnly -> false
            StreamVideoState.NotVideo -> episode?.isVideo == true
        }
        val isStarred = (episode as? PodcastEpisode)?.isStarred == true
        val isUserEpisode = episode is UserEpisode

        val isChaptersPresent: Boolean = chapters.isNotEmpty()
        val chapter: Chapter? = chapters.getChapter(positionMs.milliseconds)
        val chapterProgress: Float = chapter?.calculateProgress(positionMs.milliseconds) ?: 0f
        val chapterTimeRemaining: String = chapter?.remainingTime(
            playbackPosition = positionMs.milliseconds,
            playbackSpeed = playbackEffects.playbackSpeed,
            adjustRemainingTimeDuration = adjustRemainingTimeDuration,
        ) ?: ""
        val chapterSummary: ChapterSummaryData = chapters.getChapterSummary(positionMs.milliseconds)
        val isFirstChapter: Boolean = chapters.isFirstChapter(positionMs.milliseconds)
        val isLastChapter: Boolean = chapters.isLastChapter(positionMs.milliseconds)
        val isChapterImagePresent = chapter?.isImagePresent ?: false
        val title = chapter?.title ?: episodeTitle

        fun isPodcastArtworkVisible(): Boolean {
            return (!isVideo || isPlaybackRemote) && !isChapterImagePresent
        }

        fun isChapterArtworkVisible(): Boolean {
            return (!isVideo || isPlaybackRemote) && isChapterImagePresent
        }

        fun isVideoVisible(): Boolean {
            return isVideo && !isPlaybackRemote
        }
    }

    data class UpNextSummary(val episodeCount: Int, val totalTimeSecs: Double, val episodePlaying: Boolean)

    data class ListData(
        var podcastHeader: PlayerHeader,
        var chaptersExpanded: Boolean,
        var chapters: Chapters,
        var currentChapter: Chapter?,
        var upNextExpanded: Boolean,
        var upNextEpisodes: List<BaseEpisode>,
        var upNextSummary: UpNextSummary,
    )

    private val source = SourceView.PLAYER

    var upNextExpanded = settings.getBooleanForKey(Settings.PREFERENCE_UPNEXT_EXPANDED, true)
    var chaptersExpanded = settings.getBooleanForKey(Settings.PREFERENCE_CHAPTERS_EXPANDED, true)

    private val disposables = CompositeDisposable()

    private val playbackStateObservable: Observable<PlaybackState> = playbackManager.playbackStateRelay
        .observeOn(Schedulers.io())
    val upNextStateObservable: Observable<UpNextQueue.State> =
        playbackManager.upNextQueue.getChangesObservableWithLiveCurrentEpisode(episodeManager, podcastManager)
            .observeOn(Schedulers.io())

    private val upNextExpandedObservable = BehaviorRelay.create<Boolean>().apply { accept(upNextExpanded) }
    private val chaptersExpandedObservable = BehaviorRelay.create<Boolean>().apply { accept(chaptersExpanded) }

    private val upNextAndStreamVideoObservable = Observables.combineLatest(
        upNextStateObservable,
        playbackManager.videoRenderingEnabled.asObservable(coroutineContext),
        playbackManager.streamVideoState.asObservable(coroutineContext),
    )

    val listDataRx = Observables.combineLatest(
        upNextAndStreamVideoObservable,
        playbackStateObservable,
        settings.skipBackInSecs.flow.asObservable(coroutineContext),
        settings.skipForwardInSecs.flow.asObservable(coroutineContext),
        upNextExpandedObservable,
        chaptersExpandedObservable,
        settings.useRealTimeForPlaybackRemaingTime.flow.asObservable(coroutineContext),
        settings.artworkConfiguration.flow.asObservable(coroutineContext),
        sleepTimer.stateFlow.asObservable(coroutineContext),
        this::mergeListData,
    )
        .distinctUntilChanged()
        .toFlowable(BackpressureStrategy.LATEST)
    val listDataLive: LiveData<ListData> = listDataRx.toLiveData()
    val playingEpisodeLive: LiveData<Pair<BaseEpisode, Int>> =
        listDataRx.map { Pair(it.podcastHeader.episodeUuid, it.podcastHeader.backgroundColor) }
            .distinctUntilChanged()
            .switchMap { pair -> episodeManager.findEpisodeByUuidRxFlowable(pair.first).map { Pair(it, pair.second) } }
            .toLiveData()

    private var playbackPositionMs: Int = 0

    val upNextPlusData = upNextStateObservable.map { upNextState ->
        var episodeCount = 0
        var totalTime = 0.0
        var upNextEpisodes = emptyList<BaseEpisode>()
        var nowPlaying: BaseEpisode? = null
        if (upNextState is UpNextQueue.State.Loaded) {
            nowPlaying = upNextState.episode
            upNextEpisodes = upNextState.queue
            episodeCount = upNextState.queue.size

            val countEpisodes = listOf(nowPlaying) + upNextEpisodes
            for (countEpisode in countEpisodes) {
                totalTime += countEpisode.duration
                if (countEpisode.isInProgress) {
                    totalTime -= countEpisode.playedUpTo
                }
            }
        }
        val nowPlayingInfo: UpNextPlaying?
        nowPlayingInfo = if (nowPlaying != null) {
            UpNextPlaying(nowPlaying, (nowPlaying.playedUpTo / nowPlaying.duration).toFloat())
        } else {
            null
        }

        val upNextSummary =
            UpNextSummary(episodeCount = episodeCount, totalTimeSecs = totalTime, episodePlaying = upNextState is UpNextQueue.State.Loaded)

        return@map listOfNotNull(nowPlayingInfo, upNextSummary) + upNextEpisodes
    }

    val upNextLive: LiveData<List<Any>> = upNextPlusData.toFlowable(BackpressureStrategy.LATEST).toLiveData()

    val effectsObservable: Flowable<PodcastEffectsData> = playbackStateObservable
        .toFlowable(BackpressureStrategy.LATEST)
        .map { it.episodeUuid }
        .switchMap { episodeManager.findEpisodeByUuidRxFlowable(it) }
        .switchMap {
            if (it is PodcastEpisode) {
                podcastManager.podcastByUuidRxFlowable(it.podcastUuid)
            } else {
                Flowable.just(Podcast.userPodcast.copy(overrideGlobalEffects = false))
            }
        }
        .map { podcast ->
            val isUserPodcast = podcast.uuid == Podcast.userPodcast.uuid
            PodcastEffectsData(
                podcast = podcast,
                effects = if (podcast.overrideGlobalEffects) podcast.playbackEffects else settings.globalPlaybackEffects.value,
                showCustomEffectsSettings = !isUserPodcast,
            )
        }
        .doOnNext { Timber.i("Effects: Podcast: ${it.podcast.overrideGlobalEffects} ${it.effects}") }
        .observeOn(AndroidSchedulers.mainThread())
    val effectsLive = effectsObservable.toLiveData()

    private val _navigationState: MutableSharedFlow<NavigationState> = MutableSharedFlow()
    val navigationState = _navigationState.asSharedFlow()

    private val _snackbarMessages = MutableSharedFlow<SnackbarMessage>()
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    private val _episodeFlow = MutableStateFlow<BaseEpisode?>(null)
    var episode: BaseEpisode?
        get() = _episodeFlow.value
        set(value) {
            _episodeFlow.value = value
        }

    private val _podcastFlow = MutableStateFlow<Podcast?>(null)
    val podcastFlow = _podcastFlow.asStateFlow()
    var podcast: Podcast?
        get() = _podcastFlow.value
        set(value) {
            _podcastFlow.value = value
        }

    val isSleepRunning = MutableLiveData<Boolean>().apply { postValue(false) }
    val isSleepAtEndOfEpisodeOrChapter = MutableLiveData<Boolean>().apply { postValue(false) }
    val sleepTimeLeftText = MutableLiveData<String>()
    val sleepCustomTimeText = MutableLiveData<String>().apply {
        postValue(calcCustomTimeText())
    }
    val sleepEndOfEpisodesText = MutableLiveData<String>().apply {
        postValue(calcEndOfEpisodeText())
    }
    val sleepEndOfChaptersText = MutableLiveData<String>().apply {
        postValue(calcEndOfChapterText())
    }
    val sleepingInText = MutableLiveData<String>().apply {
        postValue(calcSleepingInEpisodesText())
    }
    var sleepCustomTimeInMinutes: Int = 5
        set(value) {
            field = value.coerceIn(1, 240)
            settings.setSleepTimerCustomMins(field)
            sleepCustomTimeText.postValue(calcCustomTimeText())
            updateSleepTimer()
        }
        get() {
            return settings.getSleepTimerCustomMins()
        }
    val playbackNotice = playbackNoticeManager.playbackNotice
        .stateIn(viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = null)

    val playerFlow = playbackManager.playerFlow

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeAd = blazeAdsManager
        .findPlayerAd()
        .stateIn(viewModelScope, started = SharingStarted.Eagerly, initialValue = null)

    fun setSleepEndOfChapters(chapters: Int = 1, shouldCallUpdateTimer: Boolean = true) {
        val newValue = chapters.coerceIn(1, 240)
        settings.setSleepEndOfChapters(newValue)
        sleepEndOfChaptersText.postValue(calcEndOfChapterText())
        sleepingInText.postValue(calcSleepingInChaptersText())
        if (shouldCallUpdateTimer) {
            updateSleepTimer()
        }
    }

    fun getSleepEndOfChapters(): Int = settings.getSleepEndOfChapters()

    fun setSleepEndOfEpisodes(episodes: Int = 1, shouldCallUpdateTimer: Boolean = true) {
        val newValue = episodes.coerceIn(1, 240)
        settings.setSleepEndOfEpisodes(newValue)
        sleepEndOfEpisodesText.postValue(calcEndOfEpisodeText())
        sleepingInText.postValue(calcSleepingInEpisodesText())
        if (shouldCallUpdateTimer) {
            updateSleepTimer()
        }
    }

    fun getSleepEndOfEpisodes(): Int = settings.getSleepEndOfEpisodes()

    init {
        updateSleepTimer()
        monitorPlaybackPosition()
    }

    private fun monitorPlaybackPosition() {
        playbackStateObservable
            .map { it.positionMs }
            .toFlowable(BackpressureStrategy.LATEST)
            .subscribeBy(
                onNext = { positionMs ->
                    playbackPositionMs = positionMs
                },
            )
            .apply {
                disposables.add(this)
            }
    }

    private fun mergeListData(
        upNextAndStreamVideo: Triple<UpNextQueue.State, Boolean, StreamVideoState>,
        playbackState: PlaybackState,
        skipBackwardInSecs: Int,
        skipForwardInSecs: Int,
        upNextExpanded: Boolean,
        chaptersExpanded: Boolean,
        adjustRemainingTimeDuration: Boolean,
        artworkConfiguration: ArtworkConfiguration,
        sleepTimerState: SleepTimerState,
    ): ListData {
        val (upNextState, videoRenderingEnabled, streamVideoState) = upNextAndStreamVideo
        val podcast: Podcast? = (upNextState as? UpNextQueue.State.Loaded)?.podcast
        val episode = (upNextState as? UpNextQueue.State.Loaded)?.episode

        this.episode = episode
        this.podcast = podcast

        val effects = PlaybackEffects().apply {
            playbackSpeed = playbackState.playbackSpeed
            trimMode = playbackState.trimMode
            isVolumeBoosted = playbackState.isVolumeBoosted
        }

        val podcastHeader: PlayerHeader
        if (episode == null) {
            podcastHeader = PlayerHeader()
        } else {
            isSleepRunning.postValue(sleepTimerState.isSleepTimerRunning)
            val playerBackground = theme.playerBackgroundColor(podcast)
            val iconTintColor = theme.playerHighlightColor(podcast)

            podcastHeader = PlayerHeader(
                positionMs = playbackState.positionMs,
                durationMs = playbackState.durationMs,
                isPlaying = playbackState.isPlaying,
                isPrepared = playbackState.isPrepared,
                episode = episode,
                streamVideoState = streamVideoState,
                videoRenderingEnabled = videoRenderingEnabled,
                isPlaybackRemote = playbackManager.isPlaybackRemote(),
                chapters = playbackState.chapters,
                backgroundColor = playerBackground,
                iconTintColor = iconTintColor,
                podcastTitle = if (playbackState.chapters.isEmpty()) podcast?.title else null,
                skipBackwardInSecs = skipBackwardInSecs,
                skipForwardInSecs = skipForwardInSecs,
                isSleepRunning = sleepTimerState.isSleepTimerRunning,
                isEffectsOn = !effects.usingDefaultValues,
                playbackEffects = effects,
                adjustRemainingTimeDuration = adjustRemainingTimeDuration,
                isBuffering = playbackState.isBuffering,
                bufferedUpToMs = playbackState.bufferedMs,
                theme = theme.activeTheme,
                useEpisodeArtwork = artworkConfiguration.useEpisodeArtwork,
            )
        }
        val chapters = playbackState.chapters
        val currentChapter = playbackState.chapters.getChapter(playbackState.positionMs.milliseconds)

        var episodeCount = 0
        var totalTime = 0.0
        var upNextEpisodes = emptyList<BaseEpisode>()
        if (upNextState is UpNextQueue.State.Loaded) {
            upNextEpisodes = upNextState.queue
            episodeCount = upNextState.queue.size
            for (upNextEpisode in upNextState.queue) {
                totalTime += upNextEpisode.duration
                if (upNextEpisode.isInProgress) {
                    totalTime -= upNextEpisode.playedUpTo
                }
            }
        }
        val upNextFooter =
            UpNextSummary(episodeCount = episodeCount, totalTimeSecs = totalTime, episodePlaying = upNextState is UpNextQueue.State.Loaded)

        return ListData(
            podcastHeader = podcastHeader,
            chaptersExpanded = chaptersExpanded,
            chapters = chapters,
            currentChapter = currentChapter,
            upNextExpanded = upNextExpanded,
            upNextEpisodes = upNextEpisodes,
            upNextSummary = upNextFooter,
        )
    }

    fun onPlayPauseClicked() {
        if (playbackManager.isPlaying()) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Pause clicked in player")
            playbackManager.pause(sourceView = source)
        } else {
            if (playbackManager.shouldWarnAboutPlayback(playbackManager.upNextQueue.currentEpisode?.uuid)) {
                viewModelScope.launch(ioDispatcher) {
                    // show the stream warning if the episode isn't downloaded
                    playbackManager.getCurrentEpisode()?.let { episode ->
                        withContext(Dispatchers.Main) {
                            if (episode.isDownloaded) {
                                play()
                                _snackbarMessages.emit(SnackbarMessage.ShowBatteryWarningIfAppropriate)
                            } else {
                                _navigationState.emit(NavigationState.ShowStreamingWarningDialog(episode))
                            }
                        }
                    }
                }
            } else {
                play()
                viewModelScope.launch {
                    _snackbarMessages.emit(SnackbarMessage.ShowBatteryWarningIfAppropriate)
                }
            }
        }
    }

    fun play() {
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Play clicked in player")
        playbackManager.playQueue(sourceView = source)
    }

    fun playEpisode(uuid: String, sourceView: SourceView = SourceView.UNKNOWN) {
        launch {
            val episode = episodeManager.findEpisodeByUuid(uuid) ?: return@launch
            playbackManager.playNow(episode = episode, sourceView = sourceView)
        }
    }

    fun onSkipBackwardClick() {
        playbackManager.skipBackward(sourceView = source, jumpAmountSeconds = settings.skipBackInSecs.value)
    }

    fun onSkipForwardClick() {
        playbackManager.skipForward(sourceView = source, jumpAmountSeconds = settings.skipForwardInSecs.value)
    }

    /**
     * SamPod mark-a-miss: tell the server there's an ad at the current playhead so it re-analyzes
     * that window and extends the sidecar (the learning loop). Self-contained — derives the SamPod
     * server, episode id, and token straight from the episode's overrideStreamUrl the coordinator
     * already set (http://server/sampod/audio/<id>?k=<token>), so no cross-module deps. No-op if
     * the current episode isn't SamPod-backed.
     */
    private var lastMarkAtMs = 0L

    /**
     * Bracket state for the two mark buttons (2026-07-31).
     *
     * This was briefly a single toggle button. That was the wrong call and Doug caught it the
     * same day: he tapped three times meaning "three ads" and got ONE bracketed ad plus an
     * orphan, because a tap's meaning depended on hidden state. Two buttons, each with one
     * fixed meaning, is predictable — which matters far more than saving a button.
     *
     * Both taps are HINTS, never boundaries. A tap is a reaction and lands seconds after the
     * thing it marks; the server treats the pair as a search region and snaps the real edges to
     * word-level ASR. The 07-30 ratification was largely undoing boundaries that had human lag
     * baked into them — don't re-introduce it from this end.
     */
    private var pendingMarkStartS: Double? = null
    private var pendingMarkId: String? = null
    private var pendingMarkAtMs = 0L

    /** After this long, a stale open bracket is abandoned rather than joined to a new tap. */
    private val bracketWindowMs = 5 * 60 * 1000L

    /** How long to wait for a second tap before resolving a lone tap as a single mark.
     *  Longer than a typical 30-90s ad read, so a real bracket is never cut short. */
    private val singleMarkGraceMs = 120_000L

    private var pendingMarkJob: kotlinx.coroutines.Job? = null

    /** "Ad starts here" — records the start locally. No POST: the end tap supplies the
     *  other half, and sending now would make the server analyze twice. */
    fun onMarkAdStartClick() {
        val ctx = markContext() ?: return
        pendingMarkStartS = ctx.posS
        pendingMarkId = ctx.id
        pendingMarkAtMs = System.currentTimeMillis()
        Toast.makeText(context, "Ad start marked at ${ctx.posS.toInt()}s", Toast.LENGTH_SHORT).show()
        android.util.Log.i("SamPod", "mark START ${ctx.posS}s id=${ctx.id}")
        // A start with no end must still land. Without this, forgetting the second button
        // would silently discard the mark — worse than the old single-button behavior.
        pendingMarkJob?.cancel()
        pendingMarkJob = viewModelScope.launch {
            delay(singleMarkGraceMs)
            val open = pendingMarkStartS
            if (open != null && pendingMarkId == ctx.id) {
                pendingMarkStartS = null
                pendingMarkId = null
                android.util.Log.i("SamPod", "mark: no end tap — sending as a single mark")
                sendMark(ctx.server, ctx.token, ctx.id, open, null)
            }
        }
    }

    /** "Ad ends here" — sends the bracket. With no start pending this is a plain single
     *  mark at the playhead, i.e. exactly the pre-toggle behavior. */
    fun onMarkAdEndClick() {
        val ctx = markContext() ?: return
        val open = pendingMarkStartS.takeIf {
            pendingMarkId == ctx.id &&
                System.currentTimeMillis() - pendingMarkAtMs <= bracketWindowMs
        }

        // The episode ADVANCED between the two taps. This is the normal case for an ad that
        // runs to the end of a show: it finishes, Up Next starts the next podcast, and the
        // ✓ arrives while something else is playing. Sending it against the CURRENT episode
        // would write a bogus ad into an unrelated show's sidecar — corrupting data Doug
        // never marked. Attribute it to the episode the ⚠ was pressed on, and leave the
        // now-playing episode untouched.
        val staleStart = pendingMarkStartS.takeIf { pendingMarkId != null && pendingMarkId != ctx.id }
        if (staleStart != null) {
            val ownerId = pendingMarkId!!
            Toast.makeText(context, "Ad marked at ${staleStart.toInt()}s on the previous episode",
                Toast.LENGTH_SHORT).show()
            android.util.Log.i("SamPod",
                "mark END after episode change → attributing to $ownerId, not ${ctx.id}")
            clearPendingMark()
            sendMark(ctx.server, ctx.token, ownerId, staleStart, null)
            return
        }
        clearPendingMark()

        if (open == null || open >= ctx.posS) {
            // No usable start (never tapped, stale, or an end BEFORE the start). Fall back to
            // a single mark rather than dropping the tap or inventing a backwards window.
            Toast.makeText(context, "Ad marked at ${ctx.posS.toInt()}s — re-analyzing…",
                Toast.LENGTH_SHORT).show()
            sendMark(ctx.server, ctx.token, ctx.id, ctx.posS, null)
            return
        }
        Toast.makeText(context, "Ad marked ${open.toInt()}s–${ctx.posS.toInt()}s — re-analyzing…",
            Toast.LENGTH_SHORT).show()
        sendMark(ctx.server, ctx.token, ctx.id, open, ctx.posS)
    }

    private fun clearPendingMark() {
        pendingMarkStartS = null
        pendingMarkId = null
        pendingMarkJob?.cancel()
        pendingMarkJob = null
    }

    private data class MarkContext(val server: String, val token: String,
                                   val id: String, val posS: Double)

    /** Shared preamble for both mark buttons: what is playing, where, and is SamPod on. */
    private fun markContext(): MarkContext? {
        val url = (playbackManager.getCurrentEpisode() as? PodcastEpisode)?.downloadUrl ?: run {
            Toast.makeText(context, "Nothing playing", Toast.LENGTH_SHORT).show()
            return null
        }
        val server = BuildConfig.SAMPOD_SERVER.trimEnd('/')
        val token = BuildConfig.SAMPOD_TOKEN
        if (server.isBlank() || token.isBlank()) {
            Toast.makeText(context, "SamPod not configured", Toast.LENGTH_SHORT).show()
            return null
        }
        // Debounce: each SEND fires a server-side re-analysis; rapid taps flooded it → timeouts.
        val now = System.currentTimeMillis()
        if (now - lastMarkAtMs < 1500) return null
        lastMarkAtMs = now
        val posMs = playbackManager.playbackStateRelay.blockingFirst().positionMs
        return MarkContext(server, token, sampodId(url), posMs / 1000.0)
    }

    /**
     * POST the mark. `endS == null` is the pre-2026-07-31 single-mark payload, which the
     * server still accepts unchanged — so an older app build and this one both work.
     */
    private fun sendMark(server: String, token: String, id: String,
                         startS: Double, endS: Double?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = if (endS == null) {
                    "{\"id\":\"$id\",\"timestamp\":$startS}"
                } else {
                    "{\"id\":\"$id\",\"timestamp\":$startS,\"end_timestamp\":$endS}"
                }
                val req = Request.Builder()
                    .url("$server/sampod/relearn?k=$token")
                    .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val client = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS).build()
                client.newCall(req).execute().use { resp ->
                    // resp.body is non-null in this OkHttp version — no safe call (would trip -Werror).
                    val body = resp.body.string()
                    android.util.Log.i("SamPod",
                        "mark-ad POST ${resp.code} ${startS}s..${endS ?: "-"} id=$id")
                    // SamPod increment 6b: the relearn response carries the UPDATED sidecar.
                    // Hand it to the skip engine (via the cross-module bus) so the correction
                    // applies to the currently-playing episode NOW, not on the next re-fetch.
                    if (resp.isSuccessful && body.isNotEmpty()) {
                        val sidecar = try {
                            org.json.JSONObject(body).optJSONObject("sidecar")
                        } catch (e: Exception) {
                            android.util.Log.w("SamPod", "mark-ad: relearn body parse failed: ${e.message}")
                            null
                        }
                        if (sidecar != null) {
                            SamPodRelearnBus.publish(id, sidecar.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SamPod", "mark-ad failed: ${e.message}")
            }
        }
    }

    private fun sampodId(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(16)

    fun onSkipForwardLongClick() {
        viewModelScope.launch {
            _navigationState.emit(NavigationState.ShowSkipForwardLongPressOptionsDialog)
        }
    }

    fun onMarkAsPlayedClick() {
        playbackManager.upNextQueue.currentEpisode?.let {
            markAsPlayedConfirmed(it)
        }
    }

    fun hasNextEpisode(): Boolean {
        return playbackManager.upNextQueue.queueEpisodes.isNotEmpty()
    }

    fun onNextEpisodeClick() {
        playbackManager.playNextInQueue(sourceView = source)
    }

    fun markAsPlayedConfirmed(episode: BaseEpisode, shouldShuffleUpNext: Boolean = false) {
        launch {
            episodeManager.markAsPlayedBlocking(episode, playbackManager, podcastManager, shouldShuffleUpNext)
            eventHorizon.track(
                EpisodeMarkedAsPlayedEvent(
                    episodeUuid = episode.uuid,
                    source = source.analyticsValue,
                ),
            )
        }
    }

    fun archiveConfirmed(episode: PodcastEpisode) {
        launch {
            episodeManager.archiveBlocking(episode, playbackManager, sync = true, shouldShuffleUpNext = settings.upNextShuffle.value)
            eventHorizon.track(
                EpisodeArchivedEvent(
                    episodeUuid = episode.uuid,
                    source = source.analyticsValue,
                ),
            )
        }
    }

    suspend fun createBookmarkArguments(): BookmarkArguments? {
        val episode = episode ?: return null
        val timeSecs = playbackPositionMs / 1000
        val bookmark = bookmarkManager.findByEpisodeTime(episode, timeSecs)
        val podcast = podcast
        return BookmarkArguments(
            bookmarkUuid = bookmark?.uuid,
            episodeUuid = episode.uuid,
            timeSecs = timeSecs,
            podcastColors = podcast?.let(::PodcastColors) ?: PodcastColors.ForUserEpisode,
        )
    }

    fun handleDownloadClickFromPlaybackActions(onDeleteStart: () -> Unit, onDownloadStart: () -> Unit) {
        val episode = playbackManager.upNextQueue.currentEpisode ?: return

        if (episode.isDownloadNotRequested) {
            downloadQueue.enqueue(episode.uuid, DownloadType.UserTriggered(waitForWifi = false), source)
            onDownloadStart.invoke()
        } else {
            downloadQueue.cancel(episode.uuid, source)
            onDeleteStart.invoke()
        }
    }

    fun seekToMs(seekTimeMs: Int, seekComplete: () -> Unit) {
        playbackManager.seekToTimeMs(seekTimeMs, seekComplete)
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
    }

    private fun calcCustomTimeText(): String {
        val hours = sleepCustomTimeInMinutes / 60
        val minutes = sleepCustomTimeInMinutes % 60

        return if (hours == 1 && minutes == 0) {
            context.resources.getString(LR.string.hours_singular)
        } else if (hours == 1 && minutes > 0) {
            context.resources.getString(LR.string.hour_and_minutes, minutes)
        } else if (hours > 1 && minutes == 0) {
            context.resources.getString(LR.string.hours_plural, hours)
        } else if (hours > 0) {
            context.resources.getString(LR.string.hours_and_minutes, hours, minutes)
        } else if (hours == 0 && minutes == 1) {
            context.resources.getString(LR.string.minutes_singular)
        } else {
            context.resources.getString(LR.string.minutes_plural, sleepCustomTimeInMinutes)
        }
    }

    private fun calcEndOfEpisodeText(): String {
        return if (getSleepEndOfEpisodes() == 1) {
            context.resources.getString(LR.string.player_sleep_timer_end_of_episode)
        } else {
            context.resources.getString(LR.string.player_sleep_timer_in_episode_plural, getSleepEndOfEpisodes())
        }
    }

    private fun calcEndOfChapterText(): String {
        return if (getSleepEndOfChapters() == 1) {
            context.resources.getString(LR.string.player_sleep_timer_end_of_chapter)
        } else {
            context.resources.getString(LR.string.player_sleep_timer_in_chapter_plural, getSleepEndOfChapters())
        }
    }

    private fun calcSleepingInEpisodesText(): String {
        return if (getSleepEndOfEpisodes() == 1) {
            context.resources.getString(LR.string.player_sleep_in_one_episode)
        } else {
            context.resources.getString(LR.string.player_sleep_in_episodes, getSleepEndOfEpisodes())
        }
    }

    private fun calcSleepingInChaptersText(): String {
        return if (getSleepEndOfChapters() == 1) {
            context.resources.getString(LR.string.player_sleep_in_one_chapter)
        } else {
            context.resources.getString(LR.string.player_sleep_in_chapters, getSleepEndOfChapters())
        }
    }

    fun updateSleepTimer() {
        val timeLeft = timeLeftInSeconds()
        if ((sleepTimer.state.isSleepTimerRunning && timeLeft > 0) || playbackManager.isSleepAfterEpisodeEnabled()) {
            isSleepAtEndOfEpisodeOrChapter.postValue(playbackManager.isSleepAfterEpisodeEnabled())
            sleepTimeLeftText.postValue(if (timeLeft > 0) Util.formattedSeconds(timeLeft.toDouble()) else "")
            setSleepEndOfEpisodes(sleepTimer.state.numberOfEpisodesLeft, shouldCallUpdateTimer = false)
            sleepingInText.postValue(calcSleepingInEpisodesText())
        } else if (playbackManager.isSleepAfterChapterEnabled()) {
            isSleepAtEndOfEpisodeOrChapter.postValue(playbackManager.isSleepAfterChapterEnabled())
            setSleepEndOfChapters(sleepTimer.state.numberOfChaptersLeft, shouldCallUpdateTimer = false)
            sleepingInText.postValue(calcSleepingInChaptersText())
        } else {
            isSleepAtEndOfEpisodeOrChapter.postValue(false)
            sleepTimer.updateSleepTimerStatus(false)
        }
    }

    fun timeLeftInSeconds(): Int {
        return (sleepTimer.state.timeLeft.inWholeMilliseconds / DateUtils.SECOND_IN_MILLIS).toInt()
    }

    fun sleepTimerAfter(mins: Int) {
        sleepTimer.sleepAfter(mins.toDuration(DurationUnit.MINUTES))
        LogBuffer.i(SleepTimer.TAG, "Sleep after $mins minutes configured")
    }

    fun sleepTimerAfterEpisode(episodes: Int = 1) {
        LogBuffer.i(SleepTimer.TAG, "Sleep after $episodes episodes configured")
        settings.setlastSleepEndOfEpisodes(episodes)
        sleepTimer.cancelTimer()
        sleepTimer.updateSleepTimerStatus(sleepTimeRunning = true, sleepAfterEpisodes = episodes)
    }

    fun sleepTimerAfterChapter(chapters: Int = 1) {
        LogBuffer.i(SleepTimer.TAG, "Sleep after $chapters chapters configured")
        settings.setlastSleepEndOfChapters(chapters)
        sleepTimer.cancelTimer()
        sleepTimer.updateSleepTimerStatus(sleepTimeRunning = true, sleepAfterChapters = chapters)
    }

    fun cancelSleepTimer() {
        LogBuffer.i(SleepTimer.TAG, "Cancelled sleep timer")
        sleepTimer.updateSleepTimerStatus(sleepTimeRunning = false)
        sleepTimer.cancelTimer()
    }

    fun sleepTimerAddExtraMins(mins: Int) {
        sleepTimer.addExtraTime(mins.toDuration(DurationUnit.MINUTES))
        updateSleepTimer()
    }

    fun changeUpNextEpisodes(episodes: List<BaseEpisode>) {
        playbackManager.changeUpNext(episodes)
    }

    fun saveEffects(effects: PlaybackEffects, podcast: Podcast) {
        launch {
            if (podcast.overrideGlobalEffects) {
                podcastManager.updateEffectsBlocking(podcast, effects)
            } else {
                settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
            }
            playbackManager.updatePlayerEffects(effects)
        }
    }

    fun onEffectsSettingsSegmentedTabSelected(podcast: Podcast, selectedTab: PlaybackEffectsSettingsTab) {
        val currentEpisode = playbackManager.getCurrentEpisode()
        val isCurrentPodcast = currentEpisode?.podcastOrSubstituteUuid == podcast.uuid
        if (!isCurrentPodcast) return
        viewModelScope.launch(ioDispatcher) {
            val override = selectedTab == PlaybackEffectsSettingsTab.ThisPodcast
            podcastManager.updateOverrideGlobalEffectsBlocking(podcast, override)

            val effects = if (override) podcast.playbackEffects else settings.globalPlaybackEffects.value
            podcast.overrideGlobalEffects = override
            saveEffects(effects, podcast)
        }
        trackPlaybackEffectsEvent { sourceView, contentType, settingType ->
            PlaybackEffectSettingsChangedEvent(
                source = sourceView.analyticsValue,
                contentType = contentType,
                settings = settingType,
            )
        }
    }

    fun clearUpNext(context: Context, upNextSource: UpNextSource): ClearUpNextDialog {
        val dialog = ClearUpNextDialog(
            source = upNextSource,
            removeNowPlaying = false,
            playbackManager = playbackManager,
            eventHorizon = eventHorizon,
            context = context,
        )
        val forceDarkTheme = settings.useDarkUpNextTheme.value && upNextSource != UpNextSource.UP_NEXT_TAB
        dialog.setForceDarkTheme(forceDarkTheme)
        return dialog
    }

    fun onChapterUrlClick(chapterUrl: HttpUrl) {
        viewModelScope.launch {
            _navigationState.emit(NavigationState.OpenChapterUrl(chapterUrl.toString()))
        }
    }

    fun onNextChapterClick() {
        eventHorizon.track(PlayerNextChapterTappedEvent(origin = currentChapterOrigin()))
        playbackManager.skipToNextSelectedOrLastChapter()
    }

    fun onPreviousChapterClick() {
        eventHorizon.track(PlayerPreviousChapterTappedEvent(origin = currentChapterOrigin()))
        playbackManager.skipToPreviousSelectedOrLastChapter()
    }

    private fun currentChapterOrigin() = listDataLive.value?.podcastHeader?.chapters?.origin?.toChapterOriginType()

    fun onChapterTitleClick(chapter: Chapter) {
        viewModelScope.launch {
            _navigationState.emit(NavigationState.OpenChapterAt(chapter))
        }
    }

    fun onPodcastTitleClick(episodeUuid: String, podcastUuid: String?) {
        if (podcastUuid == null) return
        eventHorizon.track(
            EpisodeDetailPodcastNameTappedEvent(
                episodeUuid = episodeUuid,
                source = EpisodeViewSource.NOW_PLAYING.analyticsValue,
            ),
        )
        viewModelScope.launch {
            _navigationState.emit(NavigationState.OpenPodcastPage(podcastUuid, source))
        }
    }

    fun trackPlaybackEffectsEvent(
        event: (SourceView, PlaybackContentType, SettingType) -> Trackable,
    ) {
        playbackManager.trackPlaybackEvent(SourceView.PLAYER_PLAYBACK_EFFECTS) { source, contentType ->
            val settingTab = if (effectsLive.value?.podcast?.overrideGlobalEffects == true) {
                PlaybackEffectsSettingsTab.ThisPodcast
            } else {
                PlaybackEffectsSettingsTab.AllPodcasts
            }
            event(source, contentType, settingTab.analyticsValue)
        }
    }

    fun trackAdImpression(ad: BlazeAd) {
        eventHorizon.track(
            BannerAdImpressionEvent(
                id = ad.id,
                location = ad.location.analyticsValue,
            ),
        )
    }

    fun trackAdTapped(ad: BlazeAd) {
        eventHorizon.track(
            BannerAdTappedEvent(
                id = ad.id,
                location = ad.location.analyticsValue,
            ),
        )
    }

    sealed interface NavigationState {
        data class ShowStreamingWarningDialog(val episode: BaseEpisode) : NavigationState
        data object ShowSkipForwardLongPressOptionsDialog : NavigationState
        data class OpenChapterAt(val chapter: Chapter) : NavigationState
        data class OpenPodcastPage(val podcastUuid: String, val source: SourceView) : NavigationState
        data class OpenChapterUrl(val chapterUrl: String) : NavigationState
    }

    sealed interface SnackbarMessage {
        data object ShowBatteryWarningIfAppropriate : SnackbarMessage
    }

    enum class PlaybackEffectsSettingsTab(
        @StringRes val labelResId: Int,
        val analyticsValue: SettingType,
    ) {
        AllPodcasts(
            labelResId = LR.string.podcasts_all,
            analyticsValue = SettingType.Global,
        ),
        ThisPodcast(
            labelResId = LR.string.podcast_this,
            analyticsValue = SettingType.Local,
        ),
    }
}
