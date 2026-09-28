package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Removes custom in-house ads (not Google SDK ads).
 *
 * - Custom popup ads: no-ops the popupwindow/x.i(a) funnel (all 4 ad types)
 *   AND the fullscreen startup dialog (dialog/k.onNext -> T carousel).
 * - Library/Music purple Plus card: hides feed cards at bind time in BOTH
 *   models that render the item_link_button layout (ButtonModel + LinkModel)
 *   when the item deeplink matches an upsell route — runtime-verified
 *   keyword is "upgrade" (`anghami://upgrade?...`). Branch-free
 *   boolean->visibility mapping (VISIBLE=0, GONE=8); the VISIBLE fallback is
 *   load-bearing because Epoxy holders are recycled. String.valueOf makes a
 *   null deeplink safe ("null" doesn't match).
 * - Settings subscribe banner: forces PreferenceHelper.getSettingsQuestion()
 *   =null (the actual banner source: server Question JSON rendered as
 *   QuestionRow) plus getUpgradeModel()=null (defense-in-depth for the
 *   subscriptions sub-screen) so the row builders skip.
 *
 * The ButtonModel card-hiding hook lived in Hide-Upsell before; it moved
 * here (regex deeplink match + LinkModel coverage) so all ad removal is
 * under this one patch. Hide-Upsell keeps nav entry, HeaderBar banner and
 * flyer callback.
 *
 * On-device verified 2026-09-26: Library card deeplink and settings button
 * route captured from logcat (see fingerprints kdoc). If a card persists,
 * its deeplink uses none of the matched keywords — capture via logcat
 * "Show communication"/"clicked on link" lines and extend the regex.
 *
 * Gap fix: GONE-ing the card at bind time left an empty adapter cell, so the
 * upgrade ButtonModel is additionally removed from the section-factory
 * output list (no model = no cell = no gap). The _bind hooks stay as
 * defense-in-depth for other screens/adapters.
 *
 * Feature-button removals (2026-09-28): TRY SING ALONG (player + song rows)
 * via isShowKaraokeUpsellButton=false; player AI MIX switch via U0
 * showMixAIButtonPlayer iget->const swap; PLAYS IN SHUFFLE header badge via
 * all 3 getHasShuffleBadge=false; playlist AI MIX button via
 * AutomixButtonModel drop in shouldInclude (same no-gap mechanism); card
 * shuffle badges (LinkNewCard/StoreCarouselSub _bind) via isShuffleMode
 * iget->const swaps. See fingerprints kdoc.
 */
@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "No-ops custom popup funnels (popupwindow + fullscreen startup dialog), hides upsell-deeplink feed cards (ButtonModel + LinkModel) at bind time, drops the settings subscribe banner by nulling its server-question/upgrade-model sources, and hides AI MIX / karaoke-upsell / shuffle-badge feature buttons.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)

    execute {
        // 1. Custom popup ads: never show (popupwindow funnel).
        PopupShowFingerprint.method.addInstructions(
            0,
            """
                return-void
            """
        )
        // 1b. Fullscreen startup dialog ("Pay with mobile line" / "Get offer"
        // carousel): never show. k is only instantiated in dialog/g.c for
        // fullscreen promos; onNext(true) only shows T.
        FullscreenDialogFingerprint.method.addInstructions(
            0,
            """
                return-void
            """
        )
        // 2a. Library purple card as ButtonModel (server APIButton payload).
        ButtonBindFingerprint.method.addInstructions(
            0,
            """
                iget-object v4, p0, Lcom/anghami/model/adapter/base/BaseModel;->item:Lcom/anghami/ghost/pojo/Model;
                check-cast v4, Lcom/anghami/ghost/pojo/APIButton;
                iget-object v4, v4, Lcom/anghami/ghost/pojo/APIButton;->deeplink:Ljava/lang/String;
                invoke-static {v4}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v4
                const-string v5, "(?i).*(subscribe|plus|premium|upsell|offer|upgrade).*"
                invoke-virtual {v4, v5}, Ljava/lang/String;->matches(Ljava/lang/String;)Z
                move-result v4
                xor-int/lit8 v4, v4, 0x1
                mul-int/lit8 v4, v4, 0x8
                rsub-int v4, v4, 0x8
                iget-object v5, p1, Lcom/anghami/model/adapter/base/BaseViewHolder;->itemView:Landroid/view/View;
                invoke-virtual {v5, v4}, Landroid/view/View;->setVisibility(I)V
            """
        )
        // 2b. Library purple card as LinkModel (server Link payload, same
        // layout). Link.deeplink is a public field; holder extends
        // BaseViewHolder so itemView resolves. Uses v4/v5 scratch
        // (_bind has .locals 8; consumed before original code runs).
        LinkBindFingerprint.method.addInstructions(
            0,
            """
                iget-object v4, p0, Lcom/anghami/model/adapter/base/BaseModel;->item:Lcom/anghami/ghost/pojo/Model;
                check-cast v4, Lcom/anghami/ghost/pojo/Link;
                iget-object v4, v4, Lcom/anghami/ghost/pojo/Link;->deeplink:Ljava/lang/String;
                invoke-static {v4}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v4
                const-string v5, "(?i).*(subscribe|plus|premium|upsell|offer|upgrade).*"
                invoke-virtual {v4, v5}, Ljava/lang/String;->matches(Ljava/lang/String;)Z
                move-result v4
                xor-int/lit8 v4, v4, 0x1
                mul-int/lit8 v4, v4, 0x8
                rsub-int v4, v4, 0x8
                iget-object v5, p1, Lcom/anghami/model/adapter/base/BaseViewHolder;->itemView:Landroid/view/View;
                invoke-virtual {v5, v4}, Landroid/view/View;->setVisibility(I)V
            """
        )
        // 3. Settings subscribe banner: no upgrade model -> no UpgradeRow
        // (subscriptions sub-screen), no settings question -> no QuestionRow
        // banner (main settings page). Both getters are null-safe-skipped by
        // their builders (verified on device).
        GetUpgradeModelFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """
        )
        GetSettingsQuestionFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """
        )
        // 4. Gap fix: drop upgrade cards from the feed pipeline so no empty
        // cell remains (GONE-ing at bind time left a gap: the model still
        // occupied its adapter position). _flatten() always runs
        // filterModels() -> shouldInclude() per model; returning false here
        // removes the model via Iterator.remove(). ButtonModel qualifies
        // (epoxy/w subclass, verified hierarchy). v0/v1 are scratch
        // (reassigned by original code before use); p1/p2 preserved.
        // NOTE: replaces an earlier attempt that patched A4/a's factory
        // returns — rejected: the method's switch payloads broke under
        // replaceInstructions ("Switch points to end of method").
        // NOTE 2 (corrected 2026-09-28): the shouldInclude param is the
        // ConfigurableModel INTERFACE, so every field read must narrow with
        // move-object + check-cast after its instanceof (an iget off the
        // interface-typed ref fails verification with "cannot access
        // instance field", which crashed every feed screen using class e).
        // Earlier "duplicate label" diagnosis was wrong — apktool renumbers
        // labels on decode, so that evidence was a mirage.
        ShouldIncludeFingerprint.method.addInstructions(
            0,
            """
                instance-of v0, p1, Lcom/anghami/model/adapter/AutomixButtonModel;
                if-eqz v0, :rmads_inc_drop
                instance-of v0, p1, Lcom/anghami/model/adapter/ButtonModel;
                if-eqz v0, :rmads_inc_link
                move-object v0, p1
                check-cast v0, Lcom/anghami/model/adapter/ButtonModel;
                iget-object v0, v0, Lcom/anghami/model/adapter/base/BaseModel;->item:Lcom/anghami/ghost/pojo/Model;
                instance-of v1, v0, Lcom/anghami/ghost/pojo/APIButton;
                if-eqz v1, :rmads_inc_keep
                check-cast v0, Lcom/anghami/ghost/pojo/APIButton;
                iget-object v0, v0, Lcom/anghami/ghost/pojo/APIButton;->deeplink:Ljava/lang/String;
                invoke-static {v0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0
                const-string v1, "(?i).*(subscribe|plus|premium|upsell|offer|upgrade).*"
                invoke-virtual {v0, v1}, Ljava/lang/String;->matches(Ljava/lang/String;)Z
                move-result v0
                if-nez v0, :rmads_inc_drop
                goto :rmads_inc_keep
                :rmads_inc_link
                instance-of v0, p1, Lcom/anghami/model/adapter/LinkModel;
                if-eqz v0, :rmads_inc_keep
                move-object v0, p1
                check-cast v0, Lcom/anghami/model/adapter/LinkModel;
                iget-object v0, v0, Lcom/anghami/model/adapter/base/BaseModel;->item:Lcom/anghami/ghost/pojo/Model;
                instance-of v1, v0, Lcom/anghami/ghost/pojo/Link;
                if-eqz v1, :rmads_inc_keep
                check-cast v0, Lcom/anghami/ghost/pojo/Link;
                iget-object v0, v0, Lcom/anghami/ghost/pojo/Link;->deeplink:Ljava/lang/String;
                invoke-static {v0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0
                const-string v1, "(?i).*(subscribe|plus|premium|upsell|offer|upgrade).*"
                invoke-virtual {v0, v1}, Ljava/lang/String;->matches(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :rmads_inc_keep
                :rmads_inc_drop
                const/4 v0, 0x0
                return v0
                :rmads_inc_keep
            """
        )
        // 5. TRY SING ALONG (player M0 container via Q0, song rows via
        // y8/a -> F8/X.j): the upsell flag is the sole gate.
        KaraokeUpsellButtonFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        // 6. Player AI MIX switch + label (G0/H0 via U0): replace the single
        // `iget-boolean v0, v0, Account;->showMixAIButtonPlayer:Z` with
        // `const/4 v0, 0x0` so the branch falls to GONE. Same-register
        // single-instruction swap (shuffleOn precedent); fail loudly if
        // absent or ambiguous.
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
        // 7. PLAYS IN SHUFFLE badge (playlist + album headers):
        // getHasShuffleBadge=false -> setShuffleBadgeView sets GONE.
        ShuffleBadgeBaseFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        ShuffleBadgePlaylistFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        ShuffleBadgeAlbumFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        // 8. Card/row shuffle badges: every isShuffleMode read becomes
        // const/4 (register taken from the matched instruction).
        // LinkModel.setSubtitleView is the exception with TWO reads — the
        // loop replaces every match, so it lists once and both swap.
        for (fingerprint in listOf(
            LinkNewCardBindFingerprint, StoreCarouselSubBindFingerprint,
            PlaylistRowSubtitleFingerprint, AlbumRowSubtitleFingerprint,
            PlaylistCardDrawableFingerprint, AlbumCardDrawableFingerprint,
            LinkCardDrawableFingerprint, LinkModelSubtitleFingerprint,
        )) {
            val cardBind = fingerprint.method
            val shuffleGets = cardBind.implementation!!.instructions
                .mapIndexedNotNull { index, ins ->
                    if (ins.opcode == Opcode.IGET_BOOLEAN && ins is Instruction22c &&
                        (ins.reference as? FieldReference)?.name == "isShuffleMode"
                    ) {
                        index to ins.registerA
                    } else {
                        null
                    }
                }
            check(shuffleGets.isNotEmpty()) {
                "expected at least 1 isShuffleMode iget in ${fingerprint.name}, found none"
            }
            for ((index, reg) in shuffleGets) {
                cardBind.replaceInstructions(index, "const/4 v$reg, 0x0")
            }
        }
        // 9. Adapter-level AutomixButtonModel strip (playlist AI MIX
        // button): S8/i.e(List) is the single set-models funnel for Epoxy
        // screens. Iterator-remove here is gap-free and covers screens that
        // bypass the section-feed filter. v0/v1 are scratch (reassigned by
        // the original code before use); p1 preserved. Labels are unique in
        // this method (rmads_auto_*).
        AdapterSetModelsFingerprint.method.addInstructions(
            0,
            """
                invoke-interface {p1}, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v0
                :rmads_auto_loop
                invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z
                move-result v1
                if-eqz v1, :rmads_auto_done
                invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v1
                instance-of v1, v1, Lcom/anghami/model/adapter/AutomixButtonModel;
                if-eqz v1, :rmads_auto_loop
                invoke-interface {v0}, Ljava/util/Iterator;->remove()V
                goto :rmads_auto_loop
                :rmads_auto_done
            """
        )
    }
}
