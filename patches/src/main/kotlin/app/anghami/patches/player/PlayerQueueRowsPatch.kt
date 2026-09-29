package app.anghami.patches.player

import app.anghami.patches.player.QueueRowInverseFingerprint
import app.anghami.patches.player.RowModelBindFingerprint
import app.anghami.patches.player.SongRowHighlightFingerprint
import app.anghami.patches.player.SongRowImageConfigFingerprint
import app.anghami.patches.player.SongRowPlayStateFingerprint
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Player: readable queue in day mode.
 *
 * The queue rows get white text + white icons through the generic
 * `isInverseColors` path, which assumed a dark player background. With the
 * cover tint gone the day-mode background is light, so unselected rows are
 * unreadable. Every `iget-boolean` of the flag in the row-bind chain gets a
 * `uiMode` day check that zeroes the flag register (night restores it to
 * 1), so day mode takes the stock non-inverse path everywhere. See the
 * fingerprints in [PlayerThemeFingerprints] for the full trace. Night mode
 * is behavior-identical.
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

        // The bind chain repaints per flag on every bind and ignores the
        // no-op above: RowModel._bind computes the textColor field (1
        // site), SongRowModel.removeSongHighlight repaints title/subtitle/
        // action icons after super._bind (6 sites), updatePlayState paints
        // the equalizer + row text on play-state changes (2 sites), and
        // getImageConfiguration picks the cover placeholder (1 site).
        //
        // After every isInverseColors read, zeroes the flag register in day
        // mode (restores it to 1 at night). Only that register is touched
        // and only between the read and its test, so no `.locals` change is
        // needed. Insertions run last-site-first so indices stay valid.
        // (A lambda so Fingerprint.method resolves against this receiver.)
        val forceDayNonInverse = { fingerprint: Fingerprint, tag: String, expectedSites: Int ->
            val target = fingerprint.method
            val targetInsns = target.implementation!!.instructions
            val sites = targetInsns.indices.filter { index ->
                val insn = targetInsns[index]
                insn.opcode == Opcode.IGET_BOOLEAN && insn is Instruction22c &&
                    (insn.reference as? FieldReference)?.name == "isInverseColors"
            }
            check(sites.size == expectedSites) {
                "Expected $expectedSites isInverseColors sites for $tag, found ${sites.size}; refusing to patch"
            }
            for ((i, site) in sites.sortedDescending().withIndex()) {
                val insn = targetInsns[site] as Instruction22c
                val r = "v${insn.registerA}"
                val n = "${tag}_${i}"
                target.addInstructions(
                    site + 1,
                    """
                        if-eqz $r, :pq_day_done_$n
                        invoke-virtual {p0}, Lcom/anghami/model/adapter/base/ConfigurableModelWithHolder;->getContext()Landroid/content/Context;
                        move-result-object $r
                        invoke-virtual {$r}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                        move-result-object $r
                        invoke-virtual {$r}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                        move-result-object $r
                        iget $r, $r, Landroid/content/res/Configuration;->uiMode:I
                        and-int/lit8 $r, $r, 0x30
                        xor-int/lit8 $r, $r, 0x20
                        if-eqz $r, :pq_day_night_$n
                        const/4 $r, 0x0
                        goto :pq_day_done_$n
                        :pq_day_night_$n
                        const/4 $r, 0x1
                        :pq_day_done_$n
                    """,
                )
            }
        }
        forceDayNonInverse(RowModelBindFingerprint, "bind", 1)
        forceDayNonInverse(SongRowHighlightFingerprint, "hl", 6)
        forceDayNonInverse(SongRowPlayStateFingerprint, "ps", 2)
        forceDayNonInverse(SongRowImageConfigFingerprint, "img", 1)
    }
}
