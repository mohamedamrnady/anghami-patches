package app.anghami.patches.player

import app.anghami.patches.player.PlayerLottieBindFingerprint
import app.anghami.patches.player.ShareViewCtorFingerprint
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

/**
 * Player: day-mode action icons (like / download lotties + share).
 *
 * - Like/save/download are `LottieAnimationView`s whose assets are
 *   pure-white fills with no code tinting anywhere, and the `app:tint`
 *   resource experiment was confirmed a no-op on device. Appends a uiMode
 *   check at the end of the player view-binding method (`l.h0()`, v0/v1
 *   dead there): day mode applies a black `ImageView.setColorFilter`
 *   (white art + SRC_ATOP = black silhouette, alpha preserved, survives
 *   `setAnimation` swaps), night mode clears it. State (liked/unliked,
 *   download phases) is carried by animation file + visibility, never by
 *   color, so the filter is safe for all states.
 * - Share is `AnimatedShareView`, which hardcodes white into two `Paint`s
 *   in its constructor and draws the glyph itself. Appends a uiMode check
 *   at the end of `<init>` (v0/v1 dead before `return-void`): day mode
 *   repaints both to black, night keeps stock white.
 */
@Suppress("unused")
val playerActionIconsPatch = bytecodePatch(
    name = "Player: day-mode action icons",
    description = "Tints the like/download lottie buttons and the share button black in day mode. Night mode is unchanged.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Player theme")

    execute {
        val bind = PlayerLottieBindFingerprint.method
        val bindInsns = bind.implementation!!.instructions
        check(bindInsns.last().opcode == Opcode.RETURN_VOID) {
            "l.h0 does not end with return-void; refusing to append"
        }
        val lotties = listOf(
            "v" to "like",
            "x" to "save",
            "z" to "download",
        )
        val lottieCode = lotties.joinToString("\n") { (field, tag) ->
            """
                iget-object v0, p0, Lcom/anghami/player/ui/l;->$field:Lcom/airbnb/lottie/LottieAnimationView;
                invoke-virtual {v0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v1
                invoke-virtual {v1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v1
                invoke-virtual {v1}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                move-result-object v1
                iget v1, v1, Landroid/content/res/Configuration;->uiMode:I
                and-int/lit8 v1, v1, 0x30
                xor-int/lit8 v1, v1, 0x20
                if-eqz v1, :lot_night_$tag
                const v1, -0x1000000
                invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setColorFilter(I)V
                goto :lot_done_$tag
                :lot_night_$tag
                invoke-virtual {v0}, Landroid/widget/ImageView;->clearColorFilter()V
                :lot_done_$tag
            """.trimIndent()
        }
        bind.addInstructions(bindInsns.size - 1, lottieCode)

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
                invoke-virtual {v0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                move-result-object v0
                iget v0, v0, Landroid/content/res/Configuration;->uiMode:I
                and-int/lit8 v0, v0, 0x30
                xor-int/lit8 v0, v0, 0x20
                if-eqz v0, :share_day_done
                iget-object v0, p0, Lcom/anghami/player/ui/AnimatedShareView;->k:Landroid/graphics/Paint;
                const v1, -0x1000000
                invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
                iget-object v0, p0, Lcom/anghami/player/ui/AnimatedShareView;->l:Landroid/graphics/Paint;
                invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
                :share_day_done
            """,
        )
    }
}
