package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/**
 * Unforces shuffle and remembers the user's shuffle choice (session-scoped).
 *
 * Sticky cell: a `public static boolean unforceShuffleRemembered` field added
 * to `PlayQueueManager` (JVM default false = in order). Session-scoped by
 * design — this matches what the app itself does (its own
 * `persistPlayQueue()` preserves the CURRENT queue's mode across restart,
 * but no new queue ever inherits shuffle in stock either).
 *
 * Writers (all explicit user intent, verified in Anghami 8.0.28):
 * - `PlayQueue.setShuffleMode(ZZ)`: single `sput-boolean p1` inserted right
 *   after the `isShuffleMode` iput — i.e. past the mode-changed + non-empty
 *   early exits — so only modes that actually apply are remembered. The only
 *   three callers are `shuffle()` (header Shuffle tap), `toggleShuffle()`
 *   (queue/car/bottom-sheet/song-card toggles) and `setShuffle(Z)`
 *   (manager toggle entry). Server sync writes via direct iput and never
 *   reaches the hook.
 * - Header clicks live in the "Header Play + Shuffle" patch (Play -> false,
 *   Shuffle -> true), which depends on this one.
 *
 * Readers: both server-sync points (`fillFromSyncData` /
 * `updateFromSocketPayload`) have their `iget-boolean <reg>,
 * ServerPlayQueue;->shuffleOn` REPLACED with
 * `sget-boolean <reg>, ...unforceShuffleRemembered` (same register,
 * single-instruction swap). Subclass overrides (Album/Playlist/Song/Generic/
 * Radio) all delegate to `super.fillFromSyncData`, so the base hook covers
 * them. Tap-a-song therefore follows the last user choice instead of always
 * starting in order.
 *
 * Deliberately NOT hooked: `PlayQueue.shuffle()`V. Its former no-op broke
 * the header Shuffle button, and every call site is gated on an explicit
 * user request (`c.play` v3-gate from `playFromHeader(true)`, `k5/f$a`
 * p1-gate from the same tap, callerless `shuffleCurrent()`), so there is no
 * automatic path left to kill. The Shuffle button shuffles for real again.
 *
 * Kept as-is: `shouldPlayRadio`->false (pick-a-song stays on-demand),
 * `canShuffleCurrentQueue`->true (buttons never grey out),
 * `RadioPlayQueue.shouldShowShuffleMessage`->false (no shuffle upsell).
 *
 * Patch-authoring rules honored: no new labels anywhere (sput/sget swaps and
 * the branch-free mid-method sput need none), single-register ops only.
 */
const val STICKY_SHUFFLE_FIELD = "unforceShuffleRemembered"
const val STICKY_SHUFFLE_HOLDER = "Lcom/anghami/odin/playqueue/PlayQueueManager;"

@Suppress("unused")
val unforceShufflePatch = bytecodePatch(
    name = "Unforce shuffle",
    description = "Playlists/albums start in order, the header Shuffle button shuffles for real, and new queues remember your last shuffle choice (session-scoped). Manual toggle keeps working.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)

    execute {
        // 0. Sticky cell (must exist before any sput/sget references it).
        val manager = mutableClassDefBy(STICKY_SHUFFLE_HOLDER)
        check(manager.staticFields.none { it.name == STICKY_SHUFFLE_FIELD }) {
            "sticky shuffle field already present on PlayQueueManager"
        }
        manager.staticFields.add(
            MutableField(
                ImmutableField(
                    STICKY_SHUFFLE_HOLDER,
                    STICKY_SHUFFLE_FIELD,
                    "Z",
                    0x9, // PUBLIC | STATIC
                    null,
                    emptyList(),
                    emptySet()
                )
            )
        )
        // 1. Server sync applies the remembered mode instead of the server's
        // shuffleOn. The target is located by scan; fail loudly if absent or
        // ambiguous instead of patching the wrong instruction.
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
            method.replaceInstructions(
                matches[0],
                "sget-boolean $register, $STICKY_SHUFFLE_HOLDER->$STICKY_SHUFFLE_FIELD:Z"
            )
        }
        // 2. Remember every applied toggle (post-guard: only modes that stick).
        val setShuffleMode = SetShuffleModeFingerprint.method
        val iputs = setShuffleMode.implementation!!.instructions
            .mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.IPUT_BOOLEAN && ins is Instruction22c &&
                    (ins.reference as? FieldReference)?.name == "isShuffleMode"
                ) {
                    index
                } else {
                    null
                }
            }
        check(iputs.size == 1) {
            "expected exactly 1 isShuffleMode iput in setShuffleMode, found ${iputs.size}"
        }
        // Branch-free mid-method insert; p1 still holds the requested mode
        // here (it is reused as scratch only further down). No new labels.
        setShuffleMode.addInstructions(
            iputs[0] + 1,
            "sput-boolean p1, $STICKY_SHUFFLE_HOLDER->$STICKY_SHUFFLE_FIELD:Z"
        )
        // Pick-a-song stays on-demand (never redirect to a radio/shuffle queue).
        ShouldPlayRadioFingerprint.method.addInstructions(
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
    }
}
