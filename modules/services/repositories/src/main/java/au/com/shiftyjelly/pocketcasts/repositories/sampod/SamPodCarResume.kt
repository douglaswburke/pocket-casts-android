package au.com.shiftyjelly.pocketcasts.repositories.sampod

/**
 * SamPod "car-mode resume after permanent focus loss" — switch + tuning.
 *
 * The problem (diagnosed from the in-app log, 2026-10-02): in the F-150 on Android Auto a
 * DOWNLOADED episode stops mid-play with `AUDIOFOCUS_LOSS` (permanent, not transient). Android
 * only tells the loser; the log shows the `remote-submix` device (the virtual device Android
 * Auto routes phone audio through) being torn down and rebuilt one second after the loss, so the
 * lead is the head unit / AA asserting focus for its own source switch, then going silent. A
 * permanent loss drops the app out of the focus stack, so no GAIN callback ever arrives and
 * upstream's resume-on-gain path never fires — the player sits paused until Doug taps Play.
 *
 * The fix (PlaybackManager.onFocusLoss): when the loss is permanent AND a car audio route is
 * connected, keep the "was playing" state and poll for up to [WINDOW_MS]: re-take focus and
 * resume ONLY while nothing else is actually playing and no call is in progress — never steal
 * from a call or another player. Off-car behaviour is unchanged. Any user pause/stop cancels it.
 *
 * Off switch, no code change: `sampod.carresume=false` in local.properties (app BuildConfig →
 * [enabled], set from PocketCastsApplication). On by default.
 */
object SamPodCarResume {
    @Volatile
    var enabled: Boolean = true

    /** How long after a permanent loss we keep trying to resume. */
    const val WINDOW_MS = 60_000L

    /** Poll interval inside the window. */
    const val POLL_MS = 2_000L

    /**
     * Consecutive car-resumes that each got stolen again within [RESTEAL_MS] before we stop
     * fighting the thief (an app that is genuinely holding focus and silent — don't loop forever).
     */
    const val MAX_CONSECUTIVE_RESTEALS = 3
    const val RESTEAL_MS = 30_000L
}
