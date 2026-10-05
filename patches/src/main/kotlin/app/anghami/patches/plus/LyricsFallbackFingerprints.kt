package app.anghami.patches.plus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Targets for the LRCLIB lyrics fallback (Anghami 8.0.28, versionCode 8000280).
 *
 * Details:
 * - `LA7/F;->onNext(Ljava/lang/Object;)V` is the GETlyrics.view API callback:
 *   logs "LyricsHelper:  loadAsync() onNext called with response not null",
 *   then calls `LA7/E;->b(song, response, false, callback)` (renders teaser
 *   when truncated) and `LA5/A;->d(response)` (saveLyrics, refuses truncated).
 * - `LA7/E;->b` logs "LyricsHelper:  onLyricsLoadSuccess() called for songId: ".
 * - `com.anghami.ui.view.A` extends FrameLayout and implements the `A7/E$a`
 *   render callback, so the callback object doubles as the long-press anchor.
 *
 * No accessFlags anywhere (exact-int match lesson, REPORT 10.5): class +
 * name + signature + content filters already pin each method.
 */
object LyricsApiOnNextFingerprint : Fingerprint(
    definingClass = "LA7/F;",
    name = "onNext",
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        string("LyricsHelper:  loadAsync() onNext called with response not null"),
        methodCall(
            definingClass = "LA7/E;",
            name = "b",
        ),
    )
)

object LyricsSuccessFingerprint : Fingerprint(
    definingClass = "LA7/E;",
    name = "b",
    returnType = "V",
    parameters = listOf(
        "Lcom/anghami/ghost/pojo/Song;",
        "Lcom/anghami/ghost/api/response/LyricsResponse;",
        "Z",
        "LA7/E\$a;",
    ),
    filters = listOf(
        string("LyricsHelper:  onLyricsLoadSuccess() called for songId: "),
    )
)

/**
 * `PlayerFragment.W0(Song)`: the per-song lyrics-button gate. Renders the
 * button enabled (alpha 1.0) only when `Song.hasLyrics` is true and automix
 * is off; otherwise alpha 0.3 + `setEnabled(false)` on the button and its
 * container. No accessFlags (class + name + signature + content pin it).
 */
object LyricsButtonGateFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/player/ui/l;",
    name = "W0",
    returnType = "V",
    parameters = listOf("Lcom/anghami/ghost/pojo/Song;"),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/ghost/pojo/Song;->hasLyrics:Z"
        ),
        methodCall(
            definingClass = "Lcom/anghami/odin/playqueue/PlayQueueManager;",
            name = "isAutoMix",
        ),
    )
)

/**
 * `A7/F.onError`: the GETlyrics.view failure path (song has nothing usable
 * on the server). Trampoline calls `maybeFetchNoResponse`, so these songs
 * enter the same placeholder -> waterfall -> manual-picker flow.
 */
object LyricsApiOnErrorFingerprint : Fingerprint(
    definingClass = "LA7/F;",
    name = "onError",
    returnType = "V",
    parameters = listOf("Ljava/lang/Throwable;"),
    filters = listOf(
        string("LyricsHelper:  loadAsync() onError called with null response"),
    )
)

/**
 * `PlayerFragment.a1(mode, ...)`: the player-mode observer. When lyrics mode
 * is requested but `!Song.hasLyrics` (or podcast), it forces the mode back
 * to Normal — so the tap fires yet the screen never shows. The podcast guard
 * stays; only the `hasLyrics` revert is nopped (same approved technique).
 */
object LyricsModeRevertFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/player/ui/l;",
    name = "a1",
    returnType = "V",
    parameters = listOf(
        "Lcom/anghami/player/ui/PlayerFragmentViewModel\$c;",
        "Z",
    ),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/ghost/pojo/Song;->hasLyrics:Z"
        ),
        methodCall(
            definingClass = "Lcom/anghami/player/ui/PlayerFragmentViewModel;",
            name = "getLastBoundSongId",
        ),
    )
)
