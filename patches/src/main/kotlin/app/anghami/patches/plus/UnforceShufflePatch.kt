package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Unforces shuffle: nothing starts shuffled unless the user taps Shuffle.
 *
 * - Server sync: BOTH server-sync points (`fillFromSyncData` /
 *   `updateFromSocketPayload`) have their `iget-boolean <reg>,
 *   ServerPlayQueue;->shuffleOn` REPLACED with `const/4 <reg>, 0x0` (same
 *   register, single-instruction swap). Subclass overrides
 *   (Album/Playlist/Song/Generic/Radio) all delegate to `super`, so the
 *   base hooks cover them. The server can never turn shuffle on — this
 *   also keeps the client from REPORTING shuffleOn=true + shuffledSongs
 *   (the report/PUT path copies `isShuffleMode` straight into
 *   `ServerPlayQueue`), which is what made the server answer with
 *   radio/restricted content and enforce skip limits despite the local
 *   unlocks.
 *
 * Deliberately NOT hooked: `PlayQueue.shuffle()`V. Its former no-op broke
 * the header Shuffle button, and every call site is gated on an explicit
 * user request (`c.play` v3-gate from `playFromHeader(true)`, `k5/f$a`
 * p1-gate from the same tap, callerless `shuffleCurrent()`), so there is no
 * automatic path left to kill. The Shuffle button shuffles for real.
 * (`buildRelatedPlayQueue`'s boolean gate calls `setIsHeader()`, never
 * `shuffle()` — verified, not a force path.)
 *
 * Kept as-is: `shouldPlayRadio`->false (pick-a-song stays on-demand),
 * `shouldPlayRelated`->false (server "related" markings never divert
 * header Play / tap-a-song into an expanding `SongPlayqueue`),
 * `canShuffleCurrentQueue`->true (buttons never grey out),
 * `RadioPlayQueue.shouldShowShuffleMessage`->false (no shuffle upsell).
 *
 * Local-authoritative index: `updateFromSocketPayload`'s unconditional
 * `ServerPlayQueue.index -> PlayQueue.index` copy is nopped. Every skip
 * runs `setIndex` → `putQueueIfNeeded` → delayed server PUT, and each PUT
 * draws a socket echo; a stale/out-of-order echo (PUT N in flight while
 * the echo of PUT N-1 lands) rewrote the live position ~1s after the tap
 * ("next song plays, queue is messed up", any skip, intermittent). The
 * race is 100% stock (present in v1 too) — this just makes local win:
 * taps/skips own the index, echoes keep updating flags only. Trade-off:
 * cross-device position-follow no longer moves our index (repeat/video/
 * remote-play flags still sync).
 *
 * No remember-anything state: an earlier sticky-shuffle cell (session
 * field + `setShuffleMode` recorder + header-click writers + builder
 * appliers + sync readers) was fully removed 2026-10-05 — every reader
 * caused a device-visible regression (tap skip, server-side radio/skip
 * limits), so queues simply start in order, always.
 *
 * Patch-authoring rules honored: no new labels anywhere (const swaps only),
 * single-register ops only.
 */

@Suppress("unused")
val unforceShufflePatch = bytecodePatch(
    name = "Unforce shuffle",
    description = "Playlists/albums start in order; the header Shuffle button shuffles for real. Manual toggle keeps working.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)

    execute {
        // 1. Server sync pinned to false at BOTH points.
        // `fillFromSyncData` runs only inside the `PlayQueue(String,
        // ServerPlayQueue, List)` constructor (pre-handoff);
        // `updateFromSocketPayload` is fed async mid-playback (socket
        // listener + handleSocketPlayqueueEvent). A sticky reader was tried
        // in fill (2026-10-05) and reverted: seeding shuffle=true there
        // makes the client REPORT shuffleOn=true + shuffledSongs to the
        // server (PlayQueue report/PUT path copies isShuffleMode straight
        // into ServerPlayQueue), and stream authorization stays server-side
        // — the server answers shuffle-seeded queues with radio/restricted
        // content and enforces skip limits despite the local unlock gates.
        // That is the "lost unlimited skips + starts a radio" regression
        // vs the old patch. The socket sticky had the same class of bug
        // (mid-play mode flip + order swap on every push). So the server
        // can never turn shuffle on; explicit user Shuffle (shuffle() /
        // toggles, which stay live for the header buttons) still reports
        // true only when the user actually asked. The targets are located
        // by scan; fail loudly if absent or ambiguous instead of patching
        // the wrong instruction.
        for ((fingerprint, register) in
            listOf(FillFromSyncDataFingerprint to "p2", SocketPayloadShuffleFingerprint to "v7")
        ) {
            val method = fingerprint.method
            val matches = method.implementation!!.instructions
                .mapIndexedNotNull { index, ins ->
                    // iget-boolean is format 22c (target + object registers).
                    if (ins.opcode == Opcode.IGET_BOOLEAN && ins is Instruction22c &&
                        (ins.reference as? FieldReference)?.name == "shuffleOn"
                    ) {
                        index
                    } else {
                        null
                    }
                }
            check(matches.size == 1) {
                "expected exactly 1 shuffleOn iget in ${fingerprint.name}, found ${matches.size}"
            }
            method.replaceInstructions(matches[0], "const/4 $register, 0x0")
        }
        // Local-authoritative index (2026-10-05): nop the unconditional
        // `iget vX, p1, ServerPlayQueue;->index` -> `iput vX, p0,
        // PlayQueue;->index` copy in updateFromSocketPayload (same method as
        // the socket shuffle hook above). Located by pattern: the server-
        // side iget is the only `index` iget off ServerPlayQueue (the rest
        // read the local field), and the very next instruction must be its
        // iput — fail loudly otherwise. Label-safe: neither instruction
        // carries a branch label (both sit between `if-nez :cond_3` and the
        // bounds check). The OOB-clamp below then validates the LOCAL index
        // (always in range — we just set it) instead of the server's.
        run {
            val method = SocketPayloadShuffleFingerprint.method
            val insns = method.implementation!!.instructions
            val gets = insns.mapIndexedNotNull { index, ins ->
                if ((ins.opcode == Opcode.IGET || ins.opcode == Opcode.IGET_OBJECT) &&
                    ins is Instruction22c &&
                    (ins.reference as? FieldReference)?.name == "index" &&
                    (ins.reference as? FieldReference)?.definingClass ==
                    "Lcom/anghami/odin/playqueue/ServerPlayQueue;"
                ) {
                    index
                } else {
                    null
                }
            }
            check(gets.size == 1) {
                "expected exactly 1 ServerPlayQueue.index iget, found ${gets.size}"
            }
            val next = insns[gets[0] + 1]
            check(next.opcode == Opcode.IPUT && next is Instruction22c &&
                (next.reference as? FieldReference)?.name == "index"
            ) {
                "expected PlayQueue.index iput right after the server iget"
            }
            method.replaceInstructions(gets[0] + 1, "nop")
        }
        // Pick-a-song stays on-demand (never redirect to a radio/shuffle queue).
        ShouldPlayRadioFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        // Related-marked taps stay on-demand too (never build the expanding
        // SongPlayqueue). Sole private def; v0 is dead at index 0, same
        // shape as shouldPlayRadio above.
        ShouldPlayRelatedFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        // Shuffle buttons (queue screen, car mode, bottom sheet) never grey out.
        CanShuffleCurrentQueueFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )
        // Never arm the "you're shuffled" upsell dialog.
        RadioShuffleMessageFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        // Expansion top-up guard (NOT a blanket kill: empty queues load
        // their initial data through here, e.g. search taps). Index-0
        // prepend (.locals 4: v0-v2 free at entry, p1 = callback); labels
        // use the noexpand_ prefix, same pattern as the header patch's
        // :keep_playlist_secondary prepend.
        QueueExpansionFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, Lcom/anghami/odin/playqueue/PlayQueue;->getSongs()Ljava/util/List;
                move-result-object v1
                invoke-interface {v1}, Ljava/util/List;->isEmpty()Z
                move-result v1
                if-eqz v1, :noexpand_allow
                instance-of v0, p0, Lcom/anghami/odin/playqueue/SongPlayqueue;
                if-nez v0, :noexpand_isradio
                goto :noexpand_block
                :noexpand_isradio
                instance-of v0, p0, Lcom/anghami/odin/playqueue/RadioPlayQueue;
                if-eqz v0, :noexpand_allow
                :noexpand_block
                new-instance v0, Ljava/lang/Throwable;
                const-string v1, "song/radio top-up blocked"
                invoke-direct {v0, v1}, Ljava/lang/Throwable;-><init>(Ljava/lang/String;)V
                invoke-interface {p1, v0}, Lcom/anghami/odin/playqueue/PlayQueue${'$'}ExpansionCallback;->onExpansionFailed(Ljava/lang/Throwable;)V
                return-void
                :noexpand_allow
            """
        )
    }
}
