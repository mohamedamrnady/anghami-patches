package app.anghami.patches.player

import app.anghami.patches.player.PlayerSongUpdateFingerprint
import app.anghami.patches.player.ShareViewCtorFingerprint
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

/**
 * Player: accent action icons (like / download lotties + share).
 *
 * All player buttons use the primary accent (`@color/app_color` =
 * 0x7f06002f: lime stock, Monet dynamic on v31+ with the Monet patch) in
 * BOTH modes — user call, replacing the earlier black-in-day/white-at-
 * night scheme.
 *
 * - Like/save/download are `LottieAnimationView`s whose assets are
 *   pure-white fills with no code tinting anywhere, and the `app:tint`
 *   resource experiment was confirmed a no-op on device. The hook is
 *   prepended at the entry of the per-song update method `l.U0()` (v0-v2
 *   dead there; each view null-checked first because `U0()` has early
 *   exits that run before binding). The accent is resolved via
 *   `Resources.getColor(app_color)` and applied as an
 *   `ImageView.setColorFilter` (white art + SRC_ATOP = accent silhouette,
 *   alpha preserved, survives `setAnimation` swaps). State
 *   (liked/unliked, download phases) is carried by animation file +
 *   visibility, never by color, so the filter is safe for all states.
 * - Share is `AnimatedShareView`, which hardcodes white into two `Paint`s
 *   in its constructor and draws the glyph itself. Straight-line
 *   branch-free code at the end of `<init>` (v0-v1 dead before
 *   `return-void`, no labels at all): resolve app_color once, set both
 *   paints unconditionally.
 *
 * Label discipline (see fingerprints): inserted label references are only
 * correct at method index 0, so mid/end-method code must be branch-free.
 * This patch needs no uiMode checks at all — accent in both modes.
 */
@Suppress("unused")
val playerActionIconsPatch = bytecodePatch(
    name = "Player: accent action icons",
    description = "Tints the like/download lottie buttons and the share button with the primary accent (lime stock, Monet dynamic) in both day and night mode.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Player theme")

    execute {
        // U0() has .locals 9; v0-v2 are dead at entry. app_color id is
        // stable (public.xml:1935).
        val update = PlayerSongUpdateFingerprint.method
        val lotties = listOf(
            "v" to "like",
            "x" to "save",
            "z" to "download",
        )
        val lottieCode = lotties.joinToString("\n") { (field, tag) ->
            """
                iget-object v0, p0, Lcom/anghami/player/ui/l;->$field:Lcom/airbnb/lottie/LottieAnimationView;
                if-eqz v0, :lot_skip_$tag
                invoke-virtual {v0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v1
                invoke-virtual {v1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v1
                const v2, 0x7f06002f
                invoke-virtual {v1, v2}, Landroid/content/res/Resources;->getColor(I)I
                move-result v1
                invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setColorFilter(I)V
                :lot_skip_$tag
            """.trimIndent()
        }
        update.addInstructions(0, lottieCode)

        // AnimatedShareView.<init> has .locals 5; v0-v1 are dead at the
        // end. Straight-line, no labels.
        val ctor = ShareViewCtorFingerprint.method
        val ctorInsns = ctor.implementation!!.instructions
        check(ctorInsns.last().opcode == Opcode.RETURN_VOID) {
            "AnimatedShareView.<init> does not end with return-void; refusing to append"
        }
        ctor.addInstructions(
            ctorInsns.size - 1,
            """
                invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v0
                invoke-virtual {v0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v0
                const v1, 0x7f06002f
                invoke-virtual {v0, v1}, Landroid/content/res/Resources;->getColor(I)I
                move-result v1
                iget-object v0, p0, Lcom/anghami/player/ui/AnimatedShareView;->k:Landroid/graphics/Paint;
                invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
                iget-object v0, p0, Lcom/anghami/player/ui/AnimatedShareView;->l:Landroid/graphics/Paint;
                invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
            """,
        )
    }
}
