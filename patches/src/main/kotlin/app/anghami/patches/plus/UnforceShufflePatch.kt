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
 * - `PlayQueue.setShuffleMode(ZZ)`: single `sput` inserted right after the
 *   `isShuffleMode` iput — i.e. past the mode-changed + non-empty early
 *   exits — so only modes that actually apply are remembered. The recorded
 *   value is `mode AND canShuffle()`: queues stock deems unshufflable
 *   (`RadioPlayQueue`, `AutomixPlayqueue` and its stock `shuffle()` no-op)
 *   stay local instead of poisoning the cell. The only three callers are
 *   `shuffle()` (header Shuffle tap), `toggleShuffle()` (queue/car/
 *   bottom-sheet/song-card toggles) and `setShuffle(Z)` (manager toggle
 *   entry). Server sync writes via direct iput and never reaches the hook.
 * - Header clicks live in the "Header Play + Shuffle" patch (Play -> false,
 *   Shuffle -> true), which depends on this one.
 *
 * Readers (constructor sync only — tap-time appliers removed 2026-10-05,
 * see section 3 in execute: their `setShuffle` armed `putQueueIfNeeded`,
 * whose delayed server PUT + socket echo rewrote the live queue's index):
 * tap-a-song always starts in order (v1-stable); sticky covers
 * constructor-built server queues, header buttons and toggles.
 * - Sync: `fillFromSyncData` (constructor-only, i.e. pre-handoff) has its
 *   `iget-boolean p2, ServerPlayQueue;->shuffleOn` REPLACED with
 *   `sget-boolean p2, ...unforceShuffleRemembered` (same register,
 *   single-instruction swap). Subclass overrides (Album/Playlist/Song/
 *   Generic/Radio) all delegate to `super.fillFromSyncData`, so the base
 *   hook covers them.
 * - Realtime socket sync (`updateFromSocketPayload`, async mid-playback)
 *   stays pinned to false: applying sticky there flips the mode under a
 *   playing queue and swaps in the server order on every push while sticky
 *   is true (tap-a-song "starts then instantly skips").
 *
 * Deliberately NOT hooked: `PlayQueue.shuffle()`V. Its former no-op broke
 * the header Shuffle button, and every call site is gated on an explicit
 * user request (`c.play` v3-gate from `playFromHeader(true)`, `k5/f$a`
 * p1-gate from the same tap, callerless `shuffleCurrent()`), so there is no
 * automatic path left to kill. The Shuffle button shuffles for real again.
 * (`buildRelatedPlayQueue`'s boolean gate calls `setIsHeader()`, never
 * `shuffle()` — verified, not a force path.)
 *
 * Kept as-is: `shouldPlayRadio`->false (pick-a-song stays on-demand),
 * `canShuffleCurrentQueue`->true (buttons never grey out),
 * `RadioPlayQueue.shouldShowShuffleMessage`->false (no shuffle upsell).
 *
 * Patch-authoring rules honored: no new labels anywhere (sput/sget swaps,
 * the branch-free mid-method recorder and the branch-free creation tails
 * need none), single-register ops only.
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
        // 1. Creation-time sync (`fillFromSyncData`, which runs only inside
        // the `PlayQueue(String, ServerPlayQueue, List)` constructor, i.e.
        // pre-handoff) applies the remembered mode instead of the server's
        // shuffleOn. The target is located by scan; fail loudly if absent or
        // ambiguous instead of patching the wrong instruction.
        run {
            val method = FillFromSyncDataFingerprint.method
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
                "expected exactly 1 shuffleOn iget in FillFromSyncData, found ${matches.size}"
            }
            method.replaceInstructions(
                matches[0],
                "sget-boolean p2, $STICKY_SHUFFLE_HOLDER->$STICKY_SHUFFLE_FIELD:Z"
            )
        }
        // 1b. Realtime socket sync (`updateFromSocketPayload`, fed by the
        // socket listener + handleSocketPlayqueueEvent, i.e. ASYNC
        // mid-playback) is pinned to false, NOT sticky. Applying the cell
        // here flips isShuffleMode under a playing queue and swaps in the
        // server's shuffledSongs order whenever a push arrives while sticky
        // is true — the tapped song "starts and instantly skips" (seen on
        // device 2026-10-05). The server can never flip mode mid-play; local
        // toggles and creation-time seeding still work.
        run {
            val method = SocketPayloadShuffleFingerprint.method
            val matches = method.implementation!!.instructions
                .mapIndexedNotNull { index, ins ->
                    if (ins.opcode == Opcode.IGET_BOOLEAN && ins is Instruction22c &&
                        (ins.reference as? FieldReference)?.name == "shuffleOn"
                    ) {
                        index
                    } else {
                        null
                    }
                }
            check(matches.size == 1) {
                "expected exactly 1 shuffleOn iget in SocketPayloadShuffle, found ${matches.size}"
            }
            method.replaceInstructions(matches[0], "const/4 v7, 0x0")
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
        // Branch-free mid-method insert recording `mode AND canShuffle()`;
        // v1 is dead here (log-prologue scratch; next stock write is
        // `:cond_2`'s const/4 v1) and p1 still holds the requested mode
        // (reused as scratch only further down). Radio/Automix queues
        // (canShuffle()==false) therefore record false and stay local
        // instead of poisoning the cell. No new labels.
        setShuffleMode.addInstructions(
            iputs[0] + 1,
            """
                invoke-virtual {p0}, Lcom/anghami/odin/playqueue/PlayQueue;->canShuffle()Z
                move-result v1
                and-int/2addr v1, p1
                sput-boolean v1, $STICKY_SHUFFLE_HOLDER->$STICKY_SHUFFLE_FIELD:Z
            """
        )
        // 3. Fresh on-demand builders (`createPlayQueue`,
        // `buildRelatedPlayQueue`, `k5/f$a.onNext`) are DELIBERATELY
        // untouched (2026-10-05): calling public `setShuffle(sticky)` here
        // worked mechanically but armed `putQueueIfNeeded` at build time,
        // whose delayed server PUT + socket echo rewrites the LIVE queue's
        // index (`ServerPlayQueue.index -> PlayQueue.index`) ~1s after the
        // tap whenever sticky is true — the tapped song "starts and
        // instantly skips" to the server's index. Tap-a-song therefore
        // always starts in order (v1-stable behavior); sticky still covers
        // constructor-built server queues (fill), header buttons and
        // toggles. A put-free tap-time shuffle would need the order/index
        // surgery inlined without `setShuffleMode`'s put side effect —
        // parked until the echo/index race is tamed.
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
