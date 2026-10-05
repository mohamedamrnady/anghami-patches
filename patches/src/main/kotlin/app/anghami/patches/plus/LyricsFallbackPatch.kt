package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FALLBACK_HELPER =
    "Lapp/anghami/extension/extension/LrclibFallback;"

/**
 * LRCLIB fallback for truncated lyrics (opt-in, default off).
 *
 * Fires only from the GETlyrics.view API callback (`A7/F.onNext`), after the
 * server response has rendered. The helper returns early for full native
 * responses, so the Plus/native path and `saveLyrics` flow are untouched;
 * truncated teasers are never written to StoredLyrics (the helper keeps its
 * own SharedPreferences cache, `lyrics_fallback`).
 *
 * Waterfall (title then artist, sequential): exact `/api/get` with duration,
 * without duration, normalized variants, fielded `/api/search`, `?q=` search.
 * A "Full lyrics via LRCLIB" toast marks applied fallbacks.
 *
 * Patch-authoring rules honored: the trampoline is 3 branch-free
 * instructions inserted right after the `A7/E.b` static call (v0/v1 are dead
 * there — both were consumed by that call; p1 still holds the response). No
 * new labels, no register growth (.locals 3 stays valid).
 */
@Suppress("unused")
val lyricsFallbackPatch = bytecodePatch(
    name = "LRCLIB lyrics fallback",
    description = "When the server returns truncated/empty lyrics, fetches the full text from LRCLIB (opt-in free source) into a separate cache. Native full lyrics and the Plus path are untouched.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    extendWith("extensions/extension.mpe")

    execute {
        val method = LyricsApiOnNextFingerprint.method
        val insns = method.implementation!!.instructions
        val anchor = insns.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.INVOKE_STATIC && ins is Instruction35c &&
                (ins.reference as? MethodReference)?.let {
                    it.definingClass == "LA7/E;" && it.name == "b"
                } == true
            ) {
                index
            } else {
                null
            }
        }
        check(anchor.size == 1) {
            "expected exactly 1 A7/E.b call in onNext, found ${anchor.size}"
        }
        method.addInstructions(
            anchor[0] + 1,
            """
                iget-object v0, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                iget-object v1, p0, LA7/F;->b:Ljava/lang/Object;
                invoke-static {v0, p1, v1}, $FALLBACK_HELPER->maybeFetch(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )
        // Player lyrics button: never blur/disable for missing server lyrics.
        // Nops ONLY the `if-eqz hasLyrics -> blur` branch in W0(Song); the
        // automix guard below it is untouched (approved nop technique, 12.3:
        // zero-referrer labels drop at encode). Tapping then loads -> API
        // empty -> the fallback above covers it.
        run {
            val w0 = LyricsButtonGateFingerprint.method
            val w0ins = w0.implementation!!.instructions
            val gets = w0ins.mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.IGET_BOOLEAN && ins is Instruction22c &&
                    (ins.reference as? FieldReference)?.let {
                        it.definingClass == "Lcom/anghami/ghost/pojo/Song;" &&
                            it.name == "hasLyrics"
                    } == true
                ) {
                    index
                } else {
                    null
                }
            }
            check(gets.size == 1) {
                "expected exactly 1 hasLyrics iget in W0, found ${gets.size}"
            }
            check(w0ins[gets[0] + 1].opcode == Opcode.IF_EQZ) {
                "expected IF_EQZ right after hasLyrics iget in W0"
            }
            w0.replaceInstructions(gets[0] + 1, "nop")
        }
        // Mode observer: never snap lyrics mode back for missing server
        // lyrics (podcast guard stays). Same single-nop technique.
        run {
            val a1 = LyricsModeRevertFingerprint.method
            val a1ins = a1.implementation!!.instructions
            val gets = a1ins.mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.IGET_BOOLEAN && ins is Instruction22c &&
                    (ins.reference as? FieldReference)?.let {
                        it.definingClass == "Lcom/anghami/ghost/pojo/Song;" &&
                            it.name == "hasLyrics"
                    } == true
                ) {
                    index
                } else {
                    null
                }
            }
            check(gets.size == 1) {
                "expected exactly 1 hasLyrics iget in a1, found ${gets.size}"
            }
            check(a1ins[gets[0] + 1].opcode == Opcode.IF_EQZ) {
                "expected IF_EQZ right after hasLyrics iget in a1"
            }
            a1.replaceInstructions(gets[0] + 1, "nop")
        }
        // API error path (no usable server lyrics): same fallback entry with
        // a null response. Inserted before the single return-void; v0/v1 are
        // dead there (consumed by the E.a call above). Branch-free.
        run {
            val onError = LyricsApiOnErrorFingerprint.method
            val eins = onError.implementation!!.instructions
            val rets = eins.mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.RETURN_VOID) index else null
            }
            check(rets.size == 1) {
                "expected exactly 1 return in onError, found ${rets.size}"
            }
            onError.addInstructions(
                rets[0],
                """
                    iget-object v0, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                    iget-object v1, p0, LA7/F;->b:Ljava/lang/Object;
                    invoke-static {v0, v1}, $FALLBACK_HELPER->maybeFetchNoResponse(Ljava/lang/Object;Ljava/lang/Object;)V
                """
            )
        }
    }
}

/**
 * Long-press lyrics options (depends on the fallback above).
 *
 * Arms a long-press listener on the lyrics view from the same API callback:
 * shows matched source + query used, artist/title edit + retry, synced/plain
 * toggle when both exist, and a one-shot "Server version" restore of the
 * teaser. Kept separate so the fallback can run without the dialog.
 */
@Suppress("unused")
val lyricsLongPressPatch = bytecodePatch(
    name = "Lyrics long-press options",
    description = "Long-press the player LYRICS button or the lyrics view for source info, artist/title retry, synced/plain toggle, or restoring the server teaser. Pulls in 'LRCLIB lyrics fallback'.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    dependsOn(lyricsFallbackPatch)

    execute {
        val method = LyricsApiOnNextFingerprint.method
        val insns = method.implementation!!.instructions
        val anchor = insns.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.INVOKE_STATIC && ins is Instruction35c &&
                (ins.reference as? MethodReference)?.let {
                    it.definingClass == "LA7/E;" && it.name == "b"
                } == true
            ) {
                index
            } else {
                null
            }
        }
        check(anchor.size == 1) {
            "expected exactly 1 A7/E.b call in onNext, found ${anchor.size}"
        }
        method.addInstructions(
            anchor[0] + 1,
            """
                iget-object v0, p0, LA7/F;->b:Ljava/lang/Object;
                iget-object v1, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                invoke-static {v0, v1}, $FALLBACK_HELPER->armLongPress(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )
        // Arm the PLAYER button too (W0 entry: p1 is still the Song, v0 dead).
        // Branch-free prepend; Q may be null on the first call (helper
        // no-ops on non-views) and gets armed on the next song update.
        LyricsButtonGateFingerprint.method.addInstructions(
            0,
            """
                iget-object v0, p0, Lcom/anghami/player/ui/l;->Q:Landroid/widget/LinearLayout;
                invoke-static {v0, p1}, $FALLBACK_HELPER->armLongPress(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )
    }
}
