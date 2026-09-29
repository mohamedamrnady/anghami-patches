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

/**
 * Queue-row white-text target (Anghami 8.0.28, verified in Anghami 8.0.28).
 *
 * Used by the "Player: readable queue in day mode" patch.
 *
 * The queue list (`fragment_player_feed`'s `recycler_view`, rows are
 * `item_row.xml` bound through `RowModel\$RowViewHolder`) reuses the app's
 * generic song-row machinery, including its dark-surface support: the
 * player feed adapter (`S8/i`, flag `.p=true` set by `playerfeed/c.a0`)
 * propagates `ModelConfiguration.isInverseColors=true` into every queue
 * `SongRowModel`, and `RowModel\$RowViewHolder.inverseColors()` then paints
 * title + subtitle `@color/white` plus white action icons. That was
 * correct when the player background was always the dark cover color, but
 * with the cover tint removed the day-mode player background is light, so
 * unselected rows render white-on-light-grey and are unreadable (the
 * selected row keeps its white card + dark text and stays fine).
 *
 * The patch prepends a uiMode night check that returns early in day mode,
 * so rows keep their theme colors (`primaryText`/`secondaryText`, dark in
 * day mode); night mode falls through to the original white-text path,
 * which is still correct on the dark player background. Prepending at
 * index 0 is register-safe: `.locals 3` means v0-v2 are all dead at
 * method entry.
 *
 * Scope note: holders with their own `inverseColors()` override
 * (mastheads, library links, free-user/grid queue cards, store carousels)
 * do NOT route through this method and are untouched. What changes in day
 * mode is exactly the set of plain-`RowModel` rows bound with the inverse
 * flag — overwhelmingly the player queue, which is the screen this patch
 * exists for. If some other day-mode screen ever shows plain rows on a
 * dark surface with the flag set, those rows would go dark-on-dark; no
 * such screen is known (all other inverse users bring dark image/card
 * backgrounds with dedicated holders).
 */
object QueueRowInverseFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/model/adapter/base/RowModel\$RowViewHolder;",
    name = "inverseColors",
    // NOTE: no accessFlags; class + name + signature pin it.
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/model/adapter/base/RowModel\$RowViewHolder;->titleTextView:Landroid/widget/TextView;"
        ),
        methodCall(
            definingClass = "Landroid/widget/TextView;",
            name = "setTextColor",
        ),
    )
)

/**
 * Queue-row bind targets (Anghami 8.0.28, verified in Anghami 8.0.28).
 *
 * The `inverseColors()` no-op (see above) only covers the Epoxy
 * `inverseColorsOnce()` path. The white paint has three more sources that
 * run on EVERY bind and ignore that patch:
 *
 * - `RowModel._bind(RowViewHolder)` (`RowModel:280`): computes the
 *   `textColor` field (white when the inverse flag is set, `primaryText`
 *   otherwise) and paints the title with it. `setNotPlaying()` later
 *   re-applies the same field, so this one site fixes both.
 * - `SongRowModel.removeSongHighlight()` (6 flag reads): repaints title,
 *   subtitle and the more/drag/delete/like icons per flag AFTER
 *   `super._bind`, so it wins over the bind above. This is what keeps
 *   UNSELECTED queue rows white in day mode (the current-song row goes
 *   through `setSongHighlight()` with fixed colors instead, which is why
 *   it already read black).
 * - `SongRowModel.updatePlayState()` (2 reads): equalizer bar color +
 *   row text on play-state changes.
 * - `SongRowModel.getImageConfiguration()` (1 read): cover placeholder
 *   (`ph_rectangle_4d` dark vs `ph_rectangle`).
 *
 * Each patch site is a single `iget-boolean` of `isInverseColors`; the
 * patch inserts a uiMode check that zeroes that register in day mode, so
 * every downstream branch takes the stock non-inverse path (the same
 * colors every other song list in the app uses). Night mode restores the
 * flag to 1 and is byte-for-byte behavior-identical. Only the flag
 * register is touched, so no `.locals` change is needed. Insertions run
 * last-site-first so indices stay valid.
 */
object RowModelBindFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/model/adapter/base/RowModel;",
    name = "_bind",
    returnType = "V",
    parameters = listOf("Lcom/anghami/model/adapter/base/RowModel\$RowViewHolder;"),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/model/adapter/base/ConfigurableModelWithHolder;->isInverseColors:Z"
        ),
    )
)

object SongRowHighlightFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/model/adapter/SongRowModel;",
    name = "removeSongHighlight",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/model/adapter/base/ConfigurableModelWithHolder;->isInverseColors:Z"
        ),
        methodCall(
            definingClass = "Landroid/widget/TextView;",
            name = "setTextColor",
        ),
    )
)

object SongRowPlayStateFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/model/adapter/SongRowModel;",
    name = "updatePlayState",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/model/adapter/base/ConfigurableModelWithHolder;->isInverseColors:Z"
        ),
    )
)

object SongRowImageConfigFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/model/adapter/SongRowModel;",
    name = "getImageConfiguration",
    returnType = "Lg9/b;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/model/adapter/base/ConfigurableModelWithHolder;->isInverseColors:Z"
        ),
    )
)

/**
 * Day-mode action-icon targets (Anghami 8.0.28, verified in Anghami 8.0.28).
 *
 * - Like/save/download are `LottieAnimationView`s bound in
 *   `com.anghami.player.ui.l.h0()` (`l:7696-7726`, fields `v`/`x`/`z`).
 *   All four animation assets (`unlike/like`, `unsave/save`, all four
 *   download states) are pure-white fills with no code tinting anywhere
 *   (no `addValueCallback`/`setColorFilter` in the player), and the
 *   `app:tint` resource experiment was confirmed a no-op on device, so the
 *   filter has to be applied in code. The patch appends a uiMode check at
 *   the end of `h0()` (v0/v1 are dead there): day mode applies a black
 *   `ImageView.setColorFilter` (white art + SRC_ATOP black = black
 *   silhouette, alpha preserved; survives `setAnimation` swaps via
 *   `applyColorMod`), night mode clears it. Liked vs unliked states differ
 *   by animation file + visibility, never by color, so tinting is safe for
 *   all states. Karaoke/mixAI lotties are different views and untouched.
 * - Share is `AnimatedShareView`, a custom `View` that hardcodes white
 *   (`-0x1`) into two `Paint`s in its constructor (`k` line/arrow paint,
 *   `l` fill paint; the ONLY `setColor` calls in the class) and draws the
 *   glyph itself in `onDraw`. The patch appends a uiMode check at the end
 *   of `<init>(Context, AttributeSet)` (v0/v1 dead before `return-void`):
 *   day mode repaints both to black, night leaves stock white.
 */
object PlayerLottieBindFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/player/ui/l;",
    name = "h0",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/player/ui/l;->v:Lcom/airbnb/lottie/LottieAnimationView;"
        ),
        fieldAccess(
            smali = "Lcom/anghami/player/ui/l;->z:Lcom/airbnb/lottie/LottieAnimationView;"
        ),
    )
)

object ShareViewCtorFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/player/ui/AnimatedShareView;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/player/ui/AnimatedShareView;->k:Landroid/graphics/Paint;"
        ),
        methodCall(
            definingClass = "Landroid/graphics/Paint;",
            name = "setColor",
        ),
    )
)
