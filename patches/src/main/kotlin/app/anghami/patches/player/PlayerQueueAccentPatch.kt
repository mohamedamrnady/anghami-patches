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

/** Lime accent = 0x7f06005f (`branding_yellow`, stable id). */

/**
 * Player: accent now-playing row + readable pills.
 *
 * Two const swaps, no new branches (label-safe):
 *
 * - Pills (`playerfeed/c.m0`): the single `const white` feeding both the
 *   pill text int and the icon tint becomes lime, and the `const
 *   black_20_transparent` background becomes `window_background_color`
 *   (white day / dark night — the grey wash was the day complaint).
 *   Border stays null ("don't touch"), so the XML accent border survives.
 * - Now-playing (`SongRowModel.setSongHighlight`): the shared
 *   `const dark_3` (near-black title/subtitle — invisible on the dark
 *   night player) becomes lime, so title, subtitle, drag/delete icons,
 *   equalizer and video badge all follow the accent in both modes. The
 *   `#b3ffffff` row wash (ugly light-grey band at night) is zeroed to
 *   transparent — the accent text + equalizer carry the highlight.
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
    category("Experimental")

    execute {
        // --- Pills: white -> lime (text + icon tint), grey wash -> theme bg. ---
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
        m0.replaceInstructions(whiteConsts[0], "const v1, 0x7f06005f")
        val pillBgConsts = m0Insns.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.CONST &&
                (ins as? Instruction31i)?.narrowLiteral == 0x7f060046
            ) {
                index
            } else {
                null
            }
        }
        check(pillBgConsts.size == 1) {
            "expected exactly 1 black_20 const in playerfeed/c.m0, found ${pillBgConsts.size}"
        }
        m0.replaceInstructions(pillBgConsts[0], "const v0, 0x7f060679")

        // --- Now-playing: dark_3 -> lime. ---
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
        hl.replaceInstructions(darkConsts[0], "const v1, 0x7f06005f")

        // --- Equalizer: nothing to do. `setBarColor(I)` resolves the id
        // itself via `ContextCompat.getColor` (proven by the
        // `NotFoundException` a resolved color caused), so the const swap
        // above already gives it accent bars. Just assert the call site. ---
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
        check(barCalls.size == 1) {
            "expected exactly 1 EqualizerView.setBarColor in setSongHighlight, found ${barCalls.size}"
        }

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
