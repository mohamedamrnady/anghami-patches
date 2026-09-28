package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Hides upsell feature buttons (karaoke, AI MIX).
 *
 * - TRY SING ALONG (player M0 container via Q0, song rows via
 *   y8/a -> F8/X.j): isShowKaraokeUpsellButton -> false is the sole gate.
 *   The karaoke FEATURE gate (isCanUseKaraoke) is untouched.
 * - Player AI MIX switch + label (G0/H0 via U0): the single
 *   `iget-boolean v0, v0, Account;->showMixAIButtonPlayer:Z` is swapped to
 *   `const/4 v0, 0x0` so the branch falls to GONE. Same-register
 *   single-instruction swap; fails loudly if absent or ambiguous.
 * - Playlist AI MIX button: AutomixButtonModel is dropped in the S8/i
 *   adapter funnel (no model = no cell = no gap; covers Epoxy screens).
 *   Its feed-pipeline occurrence is dropped by the "Hide upgrade upsell"
 *   patch's shouldInclude filter — deliberately NOT hooked here: two
 *   patches prepending branched blocks to the same method break
 *   verification on device (VerifyError "target dex pc is not at
 *   instruction start", crash 2026-09-28). A4/a itself is untouched
 *   (switch payloads break under edit).
 *
 * See FeatureButtonsFingerprints.kt.
 */
@Suppress("unused")
val hideFeatureButtonsPatch = bytecodePatch(
    name = "Hide upsell feature buttons",
    description = "Hides TRY SING ALONG karaoke upsell, the player AI MIX switch, and the playlist AI MIX button. Feature gates untouched.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Hide Gold features")

    execute {
        KaraokeUpsellButtonFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        val u0 = PlayerAutomixSwitchFingerprint.method
        val mixAiGets = u0.implementation!!.instructions
            .mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.IGET_BOOLEAN && ins is Instruction22c &&
                    (ins.reference as? FieldReference)?.name == "showMixAIButtonPlayer"
                ) {
                    index
                } else {
                    null
                }
            }
        check(mixAiGets.size == 1) {
            "expected exactly 1 showMixAIButtonPlayer iget in U0, found ${mixAiGets.size}"
        }
        u0.replaceInstructions(mixAiGets[0], "const/4 v0, 0x0")
        // Adapter-level AutomixButtonModel strip (playlist AI MIX
        // button): S8/i.e(List) is the single set-models funnel for Epoxy
        // screens. Iterator-remove here is gap-free and covers screens that
        // bypass the section-feed filter. v0/v1 are scratch (reassigned by
        // the original code before use); p1 preserved. Labels are unique in
        // this method (hidefeat_auto_*).
        AdapterSetModelsFingerprint.method.addInstructions(
            0,
            """
                invoke-interface {p1}, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v0
                :hidefeat_auto_loop
                invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z
                move-result v1
                if-eqz v1, :hidefeat_auto_done
                invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v1
                instance-of v1, v1, Lcom/anghami/model/adapter/AutomixButtonModel;
                if-eqz v1, :hidefeat_auto_loop
                invoke-interface {v0}, Ljava/util/Iterator;->remove()V
                goto :hidefeat_auto_loop
                :hidefeat_auto_done
            """
        )
    }
}
