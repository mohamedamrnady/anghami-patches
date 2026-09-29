package app.anghami.patches.player

import app.anghami.patches.player.ShareViewCtorFingerprint
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

/**
 * Player: accent share button.
 *
 * The share button is `AnimatedShareView`, which hardcodes white into two
 * `Paint`s in its constructor and draws the glyph itself. Straight-line
 * branch-free code at the end of `<init>` (v0-v1 dead before
 * `return-void`, no labels at all): resolve `branding_yellow`
 * (0x7f06005f, lime stock / Monet dynamic on v31+) once, set both paints
 * unconditionally — accent in both modes, user call.
 *
 * (The like/save/download lotties need no bytecode: `app:tint` and
 * `ImageView.setColorFilter` are both no-ops on LottieDrawable —
 * `S3/H.setColorFilter` just logs "Use addColorFilter instead." — so the
 * resource patch sets the ctor-supported `app:lottie_colorFilter` attr
 * instead, which registers a persistent KeyPath("**") filter.)
 *
 * Label discipline (see fingerprints): inserted label references are only
 * correct at method index 0, so mid/end-method code must be branch-free.
 */
@Suppress("unused")
val playerActionIconsPatch = bytecodePatch(
    name = "Player: accent action icons",
    description = "Paints the share button with the lime accent (stock, Monet dynamic) in both day and night mode. The like/download lotties are handled via the lottie_colorFilter resource attr.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Experimental")

    execute {
        // AnimatedShareView.<init> has .locals 5; v0-v1 are dead at the
        // end. Straight-line, no labels. branding_yellow id is stable
        // (res/values/public.xml).
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
                const v1, 0x7f06005f
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
