package app.anghami.patches.player

import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch

/**
 * Player theme background: makes the player follow the app's day/night
 * theme instead of staying white-on-dark.
 *
 * The player's chrome is hardcoded white in every qualifier, so it only
 * ever read correctly on a dark background. Two new roles are introduced:
 *
 *   player_bg -> the app window background (day: white, night: #0c0d0d)
 *   player_fg -> the inverse (day: #000000, night: #ffffff)
 *   player_fg_NN -> the same foreground at the stock alpha (10/20/40/60%)
 *
 * `player_fg_NN` are single-state ColorStateLists that REFERENCE
 * `@color/player_fg`, so one file covers both modes: a `<color>` tag
 * cannot combine a reference with alpha, so the day/night split has to
 * live in the referenced color rather than in the alpha slot.
 *
 * Layouts touched: the player shell (portrait + landscape), top bar,
 * controls (portrait / landscape / land), secondary state controls (both
 * orientations), the song page and the karaoke upsell label. `textColor` /
 * `tint` / `borderColor` / `backgroundTint` slots that pointed at
 * `@color/white`, `@color/light_10`, `@color/white_60_percent_opacity` and
 * `@color/color_white_selector_becomes_black` are re-pointed, and the
 * monochrome chrome icons get an `android:tint` — they are white vector
 * drawables, so a color-slot swap alone would not move them.
 *
 * WHAT IS DELIBERATELY PRESERVED
 * - The two-tone split at the progress bar. `iv_gradient`
 *   (`@color/black_20_transparent`) and `layout_player_controls`'s
 *   `bg_color` are NOT touched, so the darker band below the seekbar
 *   survives; it is now black-20% over the theme background.
 * - `layout_player_banner.xml` (promoted-song card) keeps its own
 *   `black_80_transparent` CardView and light-on-dark text.
 * - The promoted-ad countdown ring and its drawables keep stock white
 *   (they are also used by `item_player_ad`, whose root is forced black).
 * - Branded badges (`ic_exclusive_badge`, `ic_claimed_song_badge`) are
 *   never tinted.
 * - `progress_player` / `player_seekbar_thumb` are left alone; the player
 *   gets its own `player_seekbar_progress` / `player_seekbar_thumb_theme`
 *   copies so the TV player, car mode and the volume slider keep stock
 *   white.
 * - Lyric line colors (`lyrics_line_layout` /
 *   `lyrics_line_large_layout`) are shared with the standalone
 *   `LyricsActivity`, which paints a dark gradient; flipping them here
 *   would regress that screen. Known gap, see internal notes.
 *
 * Requires the companion bytecode patch "Player: remove cover-art tint" —
 * without it the runtime cover color would overwrite `player_bg` on every
 * song change.
 *
 * Evidence: `gray_dark` is `@color/dark_10` = `#ffa1a5ac` in BOTH
 * qualifiers (`values/colors.xml:238,369`; no `values-night` override), so
 * the stock player background is a fixed light grey — which is exactly
 * why the player looked "always dark" and unrelated to the app theme.
 */
@Suppress("unused")
val playerThemePatch = resourcePatch(
    name = "Player theme background",
    description = "Makes the player background, text, icons and seekbar follow the app's day/night theme. Keeps the darker split below the progress bar. Pair with 'Player: remove cover-art tint'.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Player theme")

    execute {
        // ------------------------------------------------------------------
        // 1. Day/night roles.
        // ------------------------------------------------------------------
        // NOTE: these must be appended to the EXISTING colors.xml files.
        // The resource encoder derives the entry type from the values file
        // name (trailing "s" stripped), so a `player_colors.xml` would
        // land in a bogus `player_color` type and fail with "Undefined
        // entry name" (see MonetColorsPatch for the full story).
        appendColors("res/values/colors.xml", colorsXmlEntriesDay)
        appendColors("res/values-night/colors.xml", colorsXmlEntriesNight)

        // NOTE: the alpha roles are PLAIN <color> entries, not
        // ColorStateLists. A <color> cannot combine a reference with alpha,
        // so a CSL would be the natural fit — but AnghamiTimeBar reads the
        // custom `app:buffered_color` attr in its constructor and a CSL
        // reference there blows up with
        // `NumberFormatException: For input string: "res/color/....xml"`
        // (verified on device, 2026-09-28), taking the whole player down.
        // Literal day/night values sidestep the whole class of problem and
        // are exactly the stock alphas the player already used.
        writeNew(
            "res/color/player_fg_selector.xml",
            """<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true" android:color="@color/player_fg_60" />
    <item android:state_selected="false" android:color="@color/player_fg" />
</selector>
""",
        )

        // ------------------------------------------------------------------
        // 2. Player chrome layouts.
        // ------------------------------------------------------------------
        for (path in playerLayouts) {
            rewriteLayout(path)
        }

        // ------------------------------------------------------------------
        // 3. White-based scrims / fills: repoint the literals at the roles so
        //    they follow day/night without a -night duplicate.
        // ------------------------------------------------------------------
        repointColor("res/drawable/transparent_white_circle.xml", "#23ffffff", "@color/player_fg_20")
        repointColor("res/drawable/bg_player_karaoke_button.xml", "#23ffffff", "@color/player_fg_20")
        repointColor("res/drawable/white_background_rounded_corner_12dp.xml", "@color/white", "@color/player_fg_20")

        // ------------------------------------------------------------------
        // 4. Seekbar: new player-only drawables so the TV player, the car
        //    mode player and the volume slider keep their stock white.
        // ------------------------------------------------------------------
        writeNew("res/drawable/player_seekbar_progress.xml", seekbarProgress(theme = true))
        writeNew("res/drawable-night/player_seekbar_progress.xml", seekbarProgress(theme = false))
        writeNew("res/drawable/player_seekbar_thumb_theme.xml", seekbarThumbSelector)
        writeNew("res/drawable/player_seekbar_thumb_normal_theme.xml", seekbarThumbNormal(theme = true))
        writeNew("res/drawable/player_seekbar_thumb_pressed_theme.xml", seekbarThumbPressed(theme = true))
        writeNew("res/drawable-night/player_seekbar_thumb_normal_theme.xml", seekbarThumbNormal(theme = false))
        writeNew("res/drawable-night/player_seekbar_thumb_pressed_theme.xml", seekbarThumbPressed(theme = false))
    }
}

// ---------------------------------------------------------------------------
// Day/night role definitions
// ---------------------------------------------------------------------------

private const val colorsXmlEntriesDay = """    <color name="player_bg">@color/window_background_color</color>
    <color name="player_fg">@color/dark_1</color>
    <color name="player_fg_10">#1a000000</color>
    <color name="player_fg_20">#33000000</color>
    <color name="player_fg_40">#66000000</color>
    <color name="player_fg_60">#99000000</color>
"""

private const val colorsXmlEntriesNight = """    <color name="player_bg">@color/window_background_color</color>
    <color name="player_fg">@color/light_10</color>
    <color name="player_fg_10">#1affffff</color>
    <color name="player_fg_20">#33ffffff</color>
    <color name="player_fg_40">#66ffffff</color>
    <color name="player_fg_60">#99ffffff</color>
"""

// Monochrome white chrome icons. Tinted via android:tint because they are
// white vector drawables, not color resources. Branded badges (gold
// EXCLUSIVE, trophy CLAIMED SONG) are intentionally absent.
private val tintedIcons = setOf(
    "ic_close_player_white_34dp",
    "ic_context_white_34dp",
    "ic_explicit_white_24dp",
    "ic_previous",
    "ic_next",
    "ic_backward_15s",
    "ic_forward_30s",
    "ic_speed_1_0",
    "ic_sleep_timer",
    "ic_player_queue_selector",
    "ic_player_lyrics_selector",
    "ic_settings_filled",
    "selector_repeat_queue",
    "ic_music_video_bold",
    "ic_bsd_rbt",
    "ic_dolbyatmos",
)

private val playerLayouts = listOf(
    "res/layout/layout_player.xml",
    "res/layout-land/layout_player.xml",
    "res/layout/layout_player_controls.xml",
    "res/layout-land/layout_player_controls.xml",
    "res/layout/layout_player_controls_landscape.xml",
    "res/layout/layout_player_top_bar.xml",
    "res/layout/layout_player_state_controls.xml",
    "res/layout/layout_player_state_controls_vertical.xml",
    "res/layout/player_song_layout.xml",
    "res/layout-land/player_song_layout.xml",
)

/** Attribute-level swaps. Longest / most specific first. */
private val attributeSwaps = listOf(
    "textColor=\"@color/color_white_selector_becomes_black\"" to "textColor=\"@color/player_fg_selector\"",
    "textColor=\"@color/white_60_percent_opacity\"" to "textColor=\"@color/player_fg_60\"",
    "app:buffered_color=\"@color/white_40_percent_opacity\"" to "app:buffered_color=\"@color/player_fg_40\"",
    "app:borderColor=\"@color/white\"" to "app:borderColor=\"@color/player_fg\"",
    "app:backgroundTint=\"@color/white\"" to "app:backgroundTint=\"@color/player_fg\"",
    "app:tint=\"@color/white\"" to "app:tint=\"@color/player_fg\"",
    "android:tint=\"@color/white\"" to "android:tint=\"@color/player_fg\"",
    "textColor=\"@color/white\"" to "textColor=\"@color/player_fg\"",
    "textColor=\"@color/light_10\"" to "textColor=\"@color/player_fg\"",
    "android:background=\"@color/gray_dark\"" to "android:background=\"@color/player_bg\"",
    "android:progressDrawable=\"@drawable/progress_player\"" to "android:progressDrawable=\"@drawable/player_seekbar_progress\"",
    "android:thumb=\"@drawable/player_seekbar_thumb\"" to "android:thumb=\"@drawable/player_seekbar_thumb_theme\"",
)

// ---------------------------------------------------------------------------
// Seekbar drawables (player-only copies; stock ones stay white for the TV
// player, car mode and the volume slider).
// ---------------------------------------------------------------------------

private fun seekbarProgress(theme: Boolean): String {
    val track = if (theme) "@color/player_fg_20" else "@color/white_20_percent_opaque"
    val buffered = if (theme) "@color/player_fg_40" else "@color/white_40_percent_opacity"
    val fill = if (theme) "@color/player_fg" else "@color/white"
    return """<?xml version="1.0" encoding="utf-8"?>
<layer-list
  xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@android:id/background">
        <shape>
            <corners android:radius="360.0dip" />
            <gradient android:startColor="$track" android:endColor="$track" android:angle="270.0" android:centerY="0.75" />
        </shape>
    </item>
    <item android:id="@android:id/secondaryProgress">
        <clip>
            <shape>
                <corners android:radius="360.0dip" />
                <gradient android:startColor="$buffered" android:endColor="$buffered" android:angle="270.0" android:centerY="0.75" />
            </shape>
        </clip>
    </item>
    <item android:id="@android:id/progress">
        <clip>
            <shape>
                <corners android:radius="360.0dip" />
                <gradient android:startColor="$fill" android:endColor="$fill" android:angle="270.0" />
            </shape>
        </clip>
    </item>
</layer-list>
"""
}

private const val seekbarThumbSelector = """<?xml version="1.0" encoding="utf-8"?>
<selector
  xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true" android:drawable="@drawable/player_seekbar_thumb_pressed_theme" />
    <item android:drawable="@drawable/player_seekbar_thumb_normal_theme" />
</selector>
"""

private fun seekbarThumbNormal(theme: Boolean): String {
    val inner = if (theme) "#00000000" else "#00ffffff"
    val ring = if (theme) "@color/player_fg" else "#ffffffff"
    return """<?xml version="1.0" encoding="utf-8"?>
<layer-list
  xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="oval">
            <size android:height="14.0dip" android:width="14.0dip" />
            <solid android:color="$inner" />
        </shape>
    </item>
    <item>
        <shape android:shape="oval">
            <stroke android:height="7.0dip" android:width="7.0dip" android:color="@android:color/transparent" />
            <solid android:color="$ring" />
        </shape>
    </item>
</layer-list>
"""
}

private fun seekbarThumbPressed(theme: Boolean): String {
    val fill = if (theme) "@color/player_fg" else "#ffffffff"
    return """<?xml version="1.0" encoding="utf-8"?>
<layer-list
  xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="oval">
            <size android:height="14.0dip" android:width="14.0dip" />
            <solid android:color="$fill" />
        </shape>
    </item>
</layer-list>
"""
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun ResourcePatchContext.writeNew(path: String, content: String) {
    get(path).also { it.parentFile?.mkdirs() }.writeText(content)
}

private fun ResourcePatchContext.appendColors(path: String, entries: String) {
    val file = get(path)
    file.writeText(file.readText().replaceFirst("</resources>", "$entries</resources>"))
}

private fun ResourcePatchContext.repointColor(path: String, from: String, to: String) {
    val file = get(path)
    val text = file.readText()
    check(from in text) { "$from not found in $path" }
    file.writeText(text.replace(from, to))
}

private fun ResourcePatchContext.rewriteLayout(path: String) {
    val file = get(path)
    var text = file.readText()
    for ((from, to) in attributeSwaps) {
        text = text.replace(from, to)
    }
    // Add android:tint to the monochrome chrome icons. Decoded layouts put
    // one element per line, so a line-wise pass is enough; already-tinted
    // elements (app:tint handled above) are skipped.
    text = text.lineSequence().joinToString("\n") { line ->
        val trimmed = line.trimEnd()
        val needsTint = trimmed.endsWith("/>") &&
            !trimmed.contains("android:tint=") &&
            tintedIcons.any { icon ->
                trimmed.contains("\"@drawable/$icon\"")
            }
        if (needsTint) {
            line.trimEnd().removeSuffix("/>") + " android:tint=\"@color/player_fg\"/>"
        } else {
            line
        }
    }
    file.writeText(text)
}
