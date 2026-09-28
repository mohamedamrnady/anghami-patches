package app.anghami.patches.player

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall

/**
 * Player theme-background targets (Anghami 8.0.28, verified in Anghami 8.0.28).
 *
 * Used by the "Player theme background" patch.
 *
 * Cover-art tint — `com.anghami.player.ui.l.L0()V` (PlayerFragment, the
 * `SongViewHolder` sibling that owns `layout_player`) is the ONE place the
 * player's background is coloured from the current song:
 *
 *   Song.hexColor  (server-supplied per-song hex, `Song:310`)
 *     -> g9/i.s(Context, k9/g, String hexColor, int default, callback)
 *        (`g9/i:1016`) which either `Color.parseColor`s it or, when
 *        it is empty, extracts the dominant colour from the cover bitmap
 *        (`g9/i.f` -> `g9/h`, the "Falling back to default grayDark"
 *        error path) and then runs
 *        `view.setBackgroundColor(colour)`
 *        (`g9/i:3557-3570`) on `p0` = `player/ui/d.e` — the inflated
 *        `layout_player` root.
 *     -> the disposable is stored in `player/ui/l.n`.
 *
 * `L0()` has 3 call sites (`l:1447 / 4250 / 7002`) and is the only
 * caller of `g9/i.s` in the player; the other caller is `V5/c` (an
 * unrelated `app/base/r` screen), which is why the hook is placed here
 * rather than in `g9/i.s` itself.
 *
 * The patch removes ONLY the `g9/i.s(...)` call + its `move-result-object`
 * and substitutes `const/4 v0, 0x0`, so `player/ui/l.n` is set to null
 * (its `if-eqz` dispose guard already handles that) and the rest of
 * `L0()` — the TimeSpentTracker bookkeeping after `:cond_1` — still runs.
 */
object PlayerCoverTintFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/player/ui/l;",
    name = "L0",
    // NOTE: no accessFlags; class + name + signature pin it.
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lg9/i;",
            name = "s",
        ),
        fieldAccess(
            smali = "Lcom/anghami/player/ui/l;->n:Lvd/b;"
        ),
    )
)
