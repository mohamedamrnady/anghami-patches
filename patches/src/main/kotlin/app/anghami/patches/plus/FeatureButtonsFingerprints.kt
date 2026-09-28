package app.anghami.patches.plus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall

/**
 * Upsell feature-button targets (Anghami 8.0.28, verified in Anghami 8.0.28).
 *
 * Used by the "Hide upsell feature buttons" patch. Each hides through the
 * app's own visibility branch, so no empty cells or layout gaps remain:
 * - Player TRY SING ALONG + song-row karaoke buttons:
 *   `Account.isShowKaraokeUpsellButton()` (trivial getter on the server
 *   `showKaraokeUpsellButton` flag) feeds player/ui/l.Q0() (M0 container
 *   GONE) and y8/a.onCreateViewHolder -> F8/X(SongViewHolder).j(song)
 *   (row karaoke view GONE). The karaoke FEATURE (isCanUseKaraoke) is a
 *   separate gate, untouched.
 * - Player AI MIX switch + label: U0() shows G0/H0 only when the queue is
 *   automix-eligible AND `Account.showMixAIButtonPlayer` is true; the
 *   single iget is swapped to const/4 so the branch falls to GONE.
 * - Playlist AI MIX button: AutomixButtonModel is added unconditionally
 *   by the A4/a section factory (no client gate; server sends the
 *   section), so it is dropped gap-free in the S8/i adapter funnel (no
 *   model = no cell = no gap). Its feed-pipeline occurrence is dropped by
 *   the "Hide upgrade upsell" patch's shouldInclude filter (single-prepend
 *   constraint — see HideUpsellPatch kdoc NOTE 3). A4/a itself is
 *   untouched (switch payloads break under edit).
 */

object KaraokeUpsellButtonFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/ghost/local/Account;",
    name = "isShowKaraokeUpsellButton",
    // NOTE: no accessFlags; class + name + signature pin it.
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/anghami/ghost/local/Account;->showKaraokeUpsellButton:Z"
        ),
    )
)

object PlayerAutomixSwitchFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/player/ui/l;",
    name = "U0",
    // NOTE: no accessFlags; single U0()V def in this class. The automix
    // eligibility call pins the right method.
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/anghami/odin/automix/a;",
            name = "a",
        ),
    )
)

object AdapterSetModelsFingerprint : Fingerprint(
    definingClass = "LS8/i;",
    name = "e",
    // NOTE: no accessFlags; single e(List)V def. The M9/c.o call pins it.
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        methodCall(
            definingClass = "LM9/c;",
            name = "o",
        ),
    )
)
