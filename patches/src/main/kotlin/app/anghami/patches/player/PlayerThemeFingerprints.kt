package app.anghami.patches.player

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall

/**
 * Player theme-background targets (Anghami 8.0.28, verified in base.apk smali).
 *
 * Used by the "Player theme background" patch.
 *
 * Cover-art tint — `com.anghami.player.ui.l.L0()V` (PlayerFragment, the
 * `SongViewHolder` sibling that owns `layout_player`) is the ONE place the
 * player's background is coloured from the current song:
 *
 *   Song.hexColor  (server-supplied per-song hex, `Song.smali:310`)
 *     -> g9/i.s(Context, k9/g, String hexColor, int default, callback)
 *        (`g9/i.smali:1016`) which either `Color.parseColor`s it or, when
 *        it is empty, extracts the dominant colour from the cover bitmap
 *        (`g9/i.f` -> `g9/h`, the "Falling back to default grayDark"
 *        error path) and then runs
 *        `view.setBackgroundColor(colour)`
 *        (`g9/i.smali:3557-3570`) on `p0` = `player/ui/d.e` — the inflated
 *        `layout_player` root.
 *     -> the disposable is stored in `player/ui/l.n`.
 *
 * `L0()` has 3 call sites (`l.smali:1447 / 4250 / 7002`) and is the only
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
 * Queue-row white-text target (Anghami 8.0.28, verified in base.apk smali).
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
 * Queue-row bind target (Anghami 8.0.28, verified in base.apk smali).
 *
 * The `inverseColors()` no-op (see above) only covers the Epoxy
 * `inverseColorsOnce()` path. The white is repainted on EVERY bind by
 * `RowModel._bind` (computes the `textColor` field also re-applied by
 * `setNotPlaying()`), `SongRowModel.removeSongHighlight()` (title/
 * subtitle/icons after `super._bind` — this is what kept UNSELECTED rows
 * white while the current-song row used fixed `setSongHighlight()`
 * colors), `updatePlayState()` (equalizer) and `getImageConfiguration()`
 * (dark placeholder). All of them read the same model field, and
 * `SongRowModel._bind` calls `super` first, so forcing the field to false
 * once at the entry of `RowModel._bind` in day mode fixes every
 * downstream read on that instance (night leaves it untouched).
 * Prepended at index 0 with a single register (v0, dead at entry):
 * mid-method label references assemble to chunk-relative offsets (Morphe
 * bug that crashed h0 with VerifyError), so no mid-method branches.
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

/**
 * Player song-update target (Anghami 8.0.28, verified in base.apk smali).
 *
 * `com.anghami.player.ui.l.U0()` runs on every song/state update and owns
 * the like/save/download visibility block (`l.smali:4040-4130`, fields
 * `v`/`x`/`z`) plus the like-state sync (`i.g()`). It starts with
 * queue/song guards that can exit before the views are touched, so the
 * hook null-checks each view first. Prepended at index 0 (v0-v2 dead at
 * entry): resolves `app_color` and applies it as an
 * `ImageView.setColorFilter` in both modes.
 */
object PlayerSongUpdateFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/player/ui/l;",
    name = "U0",
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

/**
 * Day-mode action-icon targets (Anghami 8.0.28, verified in base.apk).
 *
 * - Like/save/download are `LottieAnimationView`s (fields `v`/`x`/`z`),
 *   hooked at the entry of the per-song update method `l.U0()` (see
 *   [PlayerSongUpdateFingerprint]); mid-method labels assemble to
 *   chunk-relative offsets (Morphe bug that crashed h0), so all new
 *   branches live at index 0 or use branch-free arithmetic.
 *   All four animation assets (`unlike/like`, `unsave/save`, all four
 *   download states) are pure-white fills with no code tinting anywhere
 *   (no `addValueCallback`/`setColorFilter` in the player), and the
 *   `app:tint` resource experiment was confirmed a no-op on device, so the
 *   filter has to be applied in code: `app_color` as an
 *   `ImageView.setColorFilter` in both modes. State is carried by
 *   animation file + visibility, never by color, so tinting is state-safe.
 * - Share is `AnimatedShareView`, which hardcodes white (`-0x1`) into two
 *   `Paint`s in its constructor (`k` line/arrow paint,
 *   `l` fill paint; the ONLY `setColor` calls in the class) and draws the
 *   glyph itself in `onDraw`: hooked branch-free at the end of `<init>`
 *   (app_color resolved once, both paints set unconditionally).
 */
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

/**
 * Queue pill-colors target (Anghami 8.0.28, verified in base.apk smali).
 *
 * `com.anghami.app.playerfeed.c.m0(c$d, Bundle)` (onViewHolderCreated)
 * overwrites BOTH pill buttons' `AnghamiButton.b` models AFTER inflation
 * with `g(bg=black_20_transparent, text=white, border=null, icon)` and
 * calls `d()` — which is why the XML `app:textColor` never survived
 * (white text on the light day background; border=null means "don't touch
 * the stroke", so the XML border is what you see). Single `const v1,
 * 0x7f0601fe` (white) feeds both the text int and the icon tint, so one
 * const swap to `app_color` fixes text + icons in both modes. No labels,
 * no branches: `replaceInstructions` on the const only.
 */
object QueuePillColorsFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/app/playerfeed/c;",
    name = "m0",
    returnType = "V",
    parameters = listOf("Lcom/anghami/app/playerfeed/c\$d;", "Landroid/os/Bundle;"),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/pablo/anghami_ui/AnghamiButton;->b:Ln8/g;"
        ),
        methodCall(
            definingClass = "Lcom/anghami/pablo/anghami_ui/AnghamiButton;",
            name = "d",
        ),
    )
)

/**
 * Now-playing highlight target (Anghami 8.0.28, verified in base.apk).
 *
 * `SongRowModel.setSongHighlight()` paints the playing row: title +
 * subtitle via `getColor(dark_3)` (near-black — invisible on the dark
 * night player), drag/delete icons via `setIconTintResource(dark_3)`,
 * equalizer via `setBarColor(<raw dark_3 res id as color int>)`, video
 * badge via `getColor(dark_3)` tint, and the row wash via
 * `getColor(song_row_highlight_color)` (`#b3ffffff` — the ugly light-grey
 * band in night mode). One shared `const v1, 0x7f060117`, so a single
 * const swap to `app_color` recolors title/subtitle/icons/badge to the
 * accent in both modes; the equalizer call is re-pointed at the resolved
 * color (straight-line, no labels) and the wash is zeroed to transparent.
 */
object SongHighlightFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/model/adapter/SongRowModel;",
    name = "setSongHighlight",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/model/adapter/SongRowModel;->shouldHighlightRow:Z"
        ),
        methodCall(
            definingClass = "Lcom/anghami/ui/view/EqualizerView;",
            name = "setBarColor",
        ),
    )
)
