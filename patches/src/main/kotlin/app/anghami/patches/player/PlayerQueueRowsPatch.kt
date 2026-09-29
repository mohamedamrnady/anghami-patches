package app.anghami.patches.player

import app.anghami.patches.player.QueueRowInverseFingerprint
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Player: readable queue in day mode.
 *
 * The queue rows get white text + white icons through the generic
 * `isInverseColors` path (`RowModel$RowViewHolder.inverseColors()`),
 * which assumed a dark player background. With the cover tint gone the
 * day-mode background is light, so unselected rows are unreadable. This
 * prepends a `uiMode` night check that returns early in day mode (rows
 * keep theme `primaryText`/`secondaryText`); night mode falls through to
 * the original white-text path. See [QueueRowInverseFingerprint] for the
 * full trace and scope analysis.
 */
@Suppress("unused")
val playerQueueRowsPatch = bytecodePatch(
    name = "Player: readable queue in day mode",
    description = "Keeps the player queue rows in theme text colors in day mode instead of unreadable white-on-light. Night mode is unchanged.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Player theme")

    execute {
        val inverse = QueueRowInverseFingerprint.method
        val instructions = inverse.implementation!!.instructions
        // Sanity: the method must start with the itemView iget, i.e. all
        // of v0-v2 are dead at index 0 and safe to clobber.
        val first = instructions[0]
        check(first.opcode == Opcode.IGET_OBJECT && first is Instruction22c &&
            (first.reference as? FieldReference)?.name == "itemView") {
            "RowModel\$RowViewHolder.inverseColors does not start with the itemView iget; refusing to prepend"
        }
        inverse.addInstructions(
            0,
            """
                iget-object v0, p0, Lcom/anghami/model/adapter/base/BaseViewHolder;->itemView:Landroid/view/View;
                invoke-virtual {v0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v0
                invoke-virtual {v0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v0
                invoke-virtual {v0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                move-result-object v0
                iget v0, v0, Landroid/content/res/Configuration;->uiMode:I
                and-int/lit8 v0, v0, 0x30
                const/16 v1, 0x20
                if-eq v0, v1, :player_keep_inverse
                return-void
                :player_keep_inverse
            """,
        )
    }
}
