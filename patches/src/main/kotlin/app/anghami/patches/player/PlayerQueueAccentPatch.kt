package app.anghami.patches.player

import app.anghami.patches.player.QueuePillColorsFingerprint
import app.anghami.patches.player.SongHighlightFingerprint
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31i
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** `app_color` = 0x7f06002f (stable id, `res/values/public.xml:1935`). */

/**
 * Player: accent now-playing row + readable pills.
 *
 * Two const swaps, no new branches (label-safe):
 *
 * - Pills (`playerfeed/c.m0`): the single `const white` feeding both the
 *   pill text int and the icon tint becomes `app_color`. Background wash
 *   and null border are untouched, so the XML accent border survives.
 * - Now-playing (`SongRowModel.setSongHighlight`): the shared
 *   `const dark_3` (near-black title/subtitle — invisible on the dark
 *   night player) becomes `app_color`, so title, subtitle, drag/delete
 *   icons and the video badge all follow the accent in both modes. The
 *   equalizer call passed the RAW res id as a color int, so it is
 *   re-pointed at the resolved color (straight-line, same registers).
 *   The `#b3ffffff` row wash (ugly light-grey band at night) is zeroed
 *   to transparent — the accent text + equalizer carry the highlight.
 *
 * Note: `SongRowModel` is shared by every song list in the app, so the
 * highlight change applies everywhere, not just the player queue.
 */
@Suppress("unused")
val playerQueueAccentPatch = bytecodePatch(
    name = "Player: accent now-playing + pills",
    description = "Paints the now-playing queue row and the shuffle/enhance pills with the primary accent (lime stock, Monet dynamic) instead of near-black/white. Removes the grey highlight wash.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Player theme")

    execute {
        // --- Pills: white -> app_color (text + icon tint). ---
        val m0 = QueuePillColorsFingerprint.method
        val m0Insns = m0.implementation!!.instructions
        val whiteConsts = m0Insns.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.CONST &&
                (ins as? Instruction31i)?.narrowLiteral == 0x7f0601fe
            ) {
                index
            } else {
                null
            }
        }
        check(whiteConsts.size == 1) {
            "expected exactly 1 white const in playerfeed/c.m0, found ${whiteConsts.size}"
        }
        m0.replaceInstructions(whiteConsts[0], "const v1, 0x7f06002f")

        // --- Now-playing: dark_3 -> app_color. ---
        val hl = SongHighlightFingerprint.method
        val hlInsns = hl.implementation!!.instructions
        val darkConsts = hlInsns.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.CONST &&
                (ins as? Instruction31i)?.narrowLiteral == 0x7f060117
            ) {
                index
            } else {
                null
            }
        }
        check(darkConsts.size == 1) {
            "expected exactly 1 dark_3 const in setSongHighlight, found ${darkConsts.size}"
        }
        hl.replaceInstructions(darkConsts[0], "const v1, 0x7f06002f")

        // --- Equalizer: resolve the color instead of the raw res id. ---
        val barCalls = hlInsns.mapIndexedNotNull { index, ins ->
            val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
            if (ref?.definingClass == "Lcom/anghami/ui/view/EqualizerView;" &&
                ref.name == "setBarColor"
            ) {
                index
            } else {
                null
            }
        }
        // NOTE: indices were captured before the const swap above, but a
        // 1-for-1 const replacement keeps every index valid.
        check(barCalls.size == 1) {
            "expected exactly 1 EqualizerView.setBarColor in setSongHighlight, found ${barCalls.size}"
        }
        check(hlInsns[barCalls[0]].opcode == Opcode.INVOKE_VIRTUAL) {
            "expected invoke-virtual for setBarColor, found ${hlInsns[barCalls[0]].opcode}"
        }
        hl.replaceInstructions(
            barCalls[0],
            """
                invoke-direct {p0, v1}, Lcom/anghami/model/adapter/SongRowModel;->getColor(I)I
                move-result v1
                invoke-virtual {v0, v1}, Lcom/anghami/ui/view/EqualizerView;->setBarColor(I)V
            """.trimIndent(),
        )

        // --- Highlight wash: song_row_highlight_color -> transparent. ---
        // Sequence: const v1, <wash>; getColor; move-result v1;
        // setBackgroundColor. Zero the const and drop the resolve.
        val washConsts = hl.implementation!!.instructions.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.CONST &&
                (ins as? Instruction31i)?.narrowLiteral == 0x7f06060b
            ) {
                index
            } else {
                null
            }
        }
        check(washConsts.size == 1) {
            "expected exactly 1 highlight-wash const in setSongHighlight, found ${washConsts.size}"
        }
        val washIndex = washConsts[0]
        val afterWash = hl.implementation!!.instructions
        check(afterWash[washIndex + 1].opcode == Opcode.INVOKE_DIRECT &&
            afterWash[washIndex + 2].opcode == Opcode.MOVE_RESULT) {
            "expected getColor + move-result after wash const, found " +
                "${afterWash[washIndex + 1].opcode} / ${afterWash[washIndex + 2].opcode}"
        }
        hl.removeInstruction(washIndex + 2)
        hl.removeInstruction(washIndex + 1)
        hl.replaceInstructions(washIndex, "const v1, 0x0")
    }
}
