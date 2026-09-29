package app.anghami.patches.player

import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31i
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Player theme (single toggle): all player bytecode work in one patch.
 *
 * This merges the four former player bytecode patches (cover-art tint
 * removal, readable queue rows, accent now-playing + pills, action icons)
 * so the player is one on/off switch. It `dependsOn` the "Player theme
 * background" resource patch, which carries the day/night roles and layout
 * rewrites — enabling this patch pulls that one in automatically. (A single
 * Patch object cannot cover both dex and resources: morphe-patcher 1.14.1
 * exposes only `bytecodePatch` / `resourcePatch` / `rawResourcePatch`, and
 * `BytecodePatchContext` has no resource access.)
 *
 * What it does (const swaps + index-0 prepends, no mid-method branches):
 *
 * 1. Cover-art tint removal (`player/ui/l.L0()`): the player's background
 *    is coloured from the current song in exactly one place — `Song.hexColor`
 *    (server per-song hex) into `g9/i.s(...)`, falling back to the dominant
 *    cover-bitmap colour, ending at `view.setBackgroundColor` on the
 *    `layout_player` root. The `g9/i.s(...)` range-invoke + its
 *    `move-result-object` become `const/4 v0, 0x0`, so the colour is never
 *    computed and never applied. Hooked in `L0()`, not in `g9/i.s`, because
 *    `g9/i.s` has a second caller (`V5/c`) that must keep its theming; ad
 *    pages (`F8/n`, `F8/Y`) force their own black root and are untouched.
 *    Lesson: `invoke-static/range` is dex format 3rc, not 35c — match via
 *    `ReferenceInstruction`, never a 35c cast.
 * 2. Readable queue in day mode: `RowModel$RowViewHolder.inverseColors()`
 *    returns early in day mode (night falls through to stock white), and
 *    `RowModel._bind()` forces `isInverseColors=false` in day mode — the
 *    bind chain re-reads that field on every bind and `SongRowModel._bind`
 *    calls `super` first, so one write fixes every downstream paint.
 * 3. Accent now-playing + pills: `playerfeed/c.m0` white pill text/icon
 *    const -> `primaryText`, grey wash bg -> `window_background_color`;
 *    `setSongHighlight` near-black `dark_3` -> `app_color` (title, subtitle,
 *    icons, equalizer, video badge; the equalizer self-resolves the res id,
 *    so the const swap already recolors its bars); unselected-row
 *    `app_color` -> `primaryText` so only the playing row carries the
 *    accent; the `#b3ffffff` row wash is zeroed to transparent.
 * 4. Action icons: `AnimatedShareView.<init>` resolves `primaryText` once
 *    and sets both hardcoded-white paints branch-free at ctor end; the
 *    like/download lotties get the accent `KeyPath("**")` filter
 *    re-registered after every animation set (`player/ui/j.c`,
 *    `player/ui/i.i/j`) because the XML `app:lottie_colorFilter` only
 *    sticks to the first composition (`app:tint`/`setColorFilter` are
 *    no-ops on LottieDrawable).
 *
 * Label discipline (Morphe bug that cost a device round trip): inserted
 * label references assemble to chunk-relative offsets — correct only at
 * method index 0, silently corrupt anywhere else (proven with `dexdump`:
 * a branch at dex pc `0x39C` targeted `0x1D`, `VerifyError` on launch).
 * New branches only at index 0; mid/end-method code must be branch-free.
 * All touched methods were validated with `dexdump` before install.
 */
@Suppress("unused")
val playerPatch = bytecodePatch(
    name = "Player theme",
    description = "Player theme in one toggle: removes the cover-art tint, keeps the queue readable in day mode, and paints the now-playing row, pills and action icons with the primary accent. Pulls in 'Player theme background' resources.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    category("Experimental")
    dependsOn(playerThemePatch)

    execute {
        // --- 1. Cover-art tint: drop the g9/i.s range-invoke. ---
        val l0 = PlayerCoverTintFingerprint.method
        val l0Instructions = l0.implementation!!.instructions
        // `invoke-static/range` is dex format 3rc, NOT 35c — a 35c cast
        // silently yields nothing here. ReferenceInstruction covers both.
        val g9Calls = l0Instructions.mapIndexedNotNull { index, ins ->
            val reference = (ins as? ReferenceInstruction)?.reference as? MethodReference
            if (reference?.definingClass == "Lg9/i;") {
                index to "${ins.opcode} ${reference.name}(" +
                    reference.parameterTypes.joinToString("") + ")"
            } else {
                null
            }
        }
        val tintCalls = g9Calls.filter { it.second.startsWith("INVOKE_STATIC_RANGE s(") }
        check(tintCalls.size == 1) {
            "expected exactly 1 g9/i.s range-invoke in PlayerFragment.L0, found ${tintCalls.size}; g9/i calls: $g9Calls"
        }
        val callIndex = tintCalls[0].first
        check(l0Instructions[callIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT) {
            "expected move-result-object after g9/i.s, found ${l0Instructions[callIndex + 1].opcode}"
        }
        // Drop the trailing instruction first so the call index stays valid.
        l0.removeInstruction(callIndex + 1)
        l0.replaceInstructions(callIndex, "const/4 v0, 0x0")

        // --- 2. Readable queue rows in day mode (index-0 prepends). ---
        val inverse = QueueRowInverseFingerprint.method
        val inverseInstructions = inverse.implementation!!.instructions
        // Sanity: the method must start with the itemView iget, i.e. all
        // of v0-v2 are dead at index 0 and safe to clobber.
        val first = inverseInstructions[0]
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

        // RowModel._bind has .locals 6, so v0 is dead at entry. Night
        // (xor == 0) skips the write; day forces the flag false.
        RowModelBindFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, Lcom/anghami/model/adapter/base/ConfigurableModelWithHolder;->getContext()Landroid/content/Context;
                move-result-object v0
                invoke-virtual {v0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v0
                invoke-virtual {v0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                move-result-object v0
                iget v0, v0, Landroid/content/res/Configuration;->uiMode:I
                and-int/lit8 v0, v0, 0x30
                xor-int/lit8 v0, v0, 0x20
                if-eqz v0, :pq_bind_day_done
                const/4 v0, 0x0
                iput-boolean v0, p0, Lcom/anghami/model/adapter/base/ConfigurableModelWithHolder;->isInverseColors:Z
                :pq_bind_day_done
            """,
        )

        // --- 3. Accent now-playing + pills (const swaps, no branches). ---
        // Pills: white -> primaryText (text + icon tint), grey wash -> theme bg.
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
        m0.replaceInstructions(whiteConsts[0], "const v1, 0x7f060598")
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

        // Now-playing: dark_3 -> app_color.
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

        // Unselected rows: app_color -> primaryText (title + drag/delete
        // icons share one const via move v1,v2). Inverse (night) keeps white,
        // subtitle keeps secondaryText, equalizer keeps its app_color bar.
        val rm = RemoveHighlightFingerprint.method
        val rmInsns = rm.implementation!!.instructions
        val appConsts = rmInsns.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.CONST &&
                (ins as? Instruction31i)?.narrowLiteral == 0x7f06002f
            ) {
                index
            } else {
                null
            }
        }
        check(appConsts.size == 1) {
            "expected exactly 1 app_color const in removeSongHighlight, found ${appConsts.size}"
        }
        rm.replaceInstructions(appConsts[0], "const v2, 0x7f060598")

        // Equalizer: nothing to do. `setBarColor(I)` resolves the id
        // itself via `ContextCompat.getColor` (proven by the
        // `NotFoundException` a resolved color caused), so the const swap
        // above already gives it accent bars. Just assert the call site.
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

        // Highlight wash: song_row_highlight_color -> transparent.
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

        // --- 4. Action icons (branch-free appends at exits). ---
        // AnimatedShareView.<init> has .locals 5; v0-v1 are dead at the
        // end. Straight-line, no labels. primaryText id is stable
        // (res/values/public.xml).
        val ctor = ShareViewCtorFingerprint.method
        val ctorInsns = ctor.implementation!!.instructions
        check(ctorInsns.last().opcode == Opcode.RETURN_VOID) {
            "AnimatedShareView.<init> does not end with return-void; refusing to append"
        }
        ctor.addInstructions(
            ctorInsns.size - 1,
            """
                invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v0
                invoke-virtual {v0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v0
                const v1, 0x7f060598
                invoke-virtual {v0, v1}, Landroid/content/res/Resources;->getColor(I)I
                move-result v1
                iget-object v0, p0, Lcom/anghami/player/ui/AnimatedShareView;->k:Landroid/graphics/Paint;
                invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
                iget-object v0, p0, Lcom/anghami/player/ui/AnimatedShareView;->l:Landroid/graphics/Paint;
                invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
            """,
        )

        // Lottie re-tint: re-register the accent KeyPath("**") filter after
        // every animation set on the three player-scoped funnels (see
        // LottieSetterFingerprint). Branch-free, v0-v3 only (all dead at
        // each exit). j.c takes the view in p0, i.i/i.j in p1.
        val tintFor = { viewReg: String ->
            """
                move-object v0, $viewReg
                iget-object v1, v0, Lcom/airbnb/lottie/LottieAnimationView;->e:LS3/H;
                invoke-virtual {v0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v2
                invoke-virtual {v2}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v2
                const v3, 0x7f06002f
                invoke-virtual {v2, v3}, Landroid/content/res/Resources;->getColor(I)I
                move-result v2
                sget-object v3, Landroid/graphics/PorterDuff${'$'}Mode;->SRC_ATOP:Landroid/graphics/PorterDuff${'$'}Mode;
                new-instance v0, LS3/V;
                invoke-direct {v0, v2, v3}, Landroid/graphics/PorterDuffColorFilter;-><init>(ILandroid/graphics/PorterDuff${'$'}Mode;)V
                new-instance v2, Lcom/bugsnag/android/X;
                invoke-direct {v2, v0}, Lcom/bugsnag/android/X;-><init>(LS3/V;)V
                const-string v0, "**"
                filled-new-array {v0}, [Ljava/lang/String;
                move-result-object v0
                new-instance v3, LY3/e;
                invoke-direct {v3, v0}, LY3/e;-><init>([Ljava/lang/String;)V
                sget-object v0, LS3/N;->F:Landroid/graphics/ColorFilter;
                invoke-virtual {v1, v3, v0, v2}, LS3/H;->a(LY3/e;Landroid/graphics/ColorFilter;Lcom/bugsnag/android/X;)V
            """
        }
        val jSetter = LottieSetterFingerprint.method
        val jReturns = jSetter.implementation!!.instructions.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.RETURN_VOID) index else null
        }
        check(jReturns.size == 1) {
            "expected exactly 1 return in player/ui/j.c, found ${jReturns.size}"
        }
        jSetter.addInstructions(jReturns[0], tintFor("p0"))

        for (fp in listOf(LikeAnimIFingerprint, LikeAnimJFingerprint)) {
            val m = fp.method
            val returns = m.implementation!!.instructions.mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.RETURN_VOID) index else null
            }
            check(returns.size == 2) {
                "expected exactly 2 returns in like-anim ${m}, found ${returns.size}"
            }
            // Descending so the lower index stays valid.
            for (index in returns.sortedDescending()) {
                m.addInstructions(index, tintFor("p1"))
            }
        }
    }
}
