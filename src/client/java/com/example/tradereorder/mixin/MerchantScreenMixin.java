package com.example.tradereorder.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.example.tradereorder.OrderStore;
import com.example.tradereorder.ClientMerchantMenuDuck;
import com.example.tradereorder.TradeKeys;
import com.example.tradereorder.network.ServerboundCycleTradesPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Trade reordering, per-screen (no shared global state).
 *
 * <p>Design: we physically reorder the client's copy of the merchant offer
 * list ({@code MerchantMenu.getOffers()}) into the saved display order. Because
 * each vanilla trade button selects the offer at its own list position, simply
 * reordering the backing list makes both the display and click-selection follow
 * the saved order, with no need to rewrite the buttons' {@code index} field
 * (which is {@code final} and cannot be safely mutated at runtime).</p>
 *
 * <p>State is held on the screen instance, so multiple open villagers don't
 * interfere with one another.</p>
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin
        extends AbstractContainerScreen<MerchantMenu> {

    private MerchantScreenMixin(MerchantMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Shadow private MerchantScreen.TradeOfferButton[] tradeOfferButtons;
    @Shadow private int scrollOff;
    @Shadow private int shopItem;

    @Unique private Mode tradeReorder$mode = Mode.TRADE;
    @Unique private int tradeReorder$selectedRow = -1;
    @Unique private String tradeReorder$key = null;
    @Unique private boolean tradeReorder$inited = false;
    // The server's original offer order, captured as fingerprints before any
    // reordering. Used to translate a clicked display index back to the index
    // the server expects in ServerboundSelectTradePacket.
    @Unique private final List<String> tradeReorder$original = new ArrayList<>();
    @Unique private Button tradeReorder$upButton;
    @Unique private Button tradeReorder$downButton;
    @Unique private Button tradeReorder$cycleButton;
    // Order-independent fingerprint signature of the offers captured at init.
    // When the server re-rolls a villager's trades (Cycle), the offer content
    // changes and this no longer matches, so we recapture the original order.
    @Unique private String tradeReorder$initSignature = null;

    @Unique
    private enum Mode {
        TRADE,
        REORDER,
        VIEW_ALL
    }

    @Unique
    private MerchantOffers tradeReorder$offers() {
        return ClientMerchantMenuDuck.of(this.menu).tradeReorder$getCurrentOffers();
    }

    @Unique
    private MerchantOffers tradeReorder$displayOffers() {
        return ClientMerchantMenuDuck.of(this.menu).tradeReorder$getDisplayOffers();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void tradeReorder$addButtons(CallbackInfo ci) {
        tradeReorder$key = TradeKeys.merchantKey(this.title.getString());
        tradeReorder$mode = Mode.TRADE;
        tradeReorder$selectedRow = -1;
        tradeReorder$inited = false;
        tradeReorder$initSignature = null;
        tradeReorder$original.clear();

        // Before 1.19.3 there is no Button.builder: the constructor takes the bounds.
        Button toggle = new Button(this.leftPos + 5, this.topPos - 22, 116, 18, tradeReorder$label(), b -> {
            tradeReorder$cycleMode();
            b.setMessage(tradeReorder$label());
            if (tradeReorder$mode == Mode.REORDER && tradeReorder$selectedRow < 0
                    && tradeReorder$count() > 0) {
                tradeReorder$selectedRow = 0;
            }
            tradeReorder$refresh();
        });
        this.addRenderableWidget(toggle);

        Button reset = new Button(this.leftPos + 123, this.topPos - 22, 48, 18,
                Component.literal("Reset"), b -> tradeReorder$reset());
        this.addRenderableWidget(reset);

        int bx = this.leftPos - 46;
        int by = this.topPos + 18;
        tradeReorder$upButton = new Button(bx, by, 42, 20,
                Component.literal("Up"), b -> tradeReorder$move(-1));
        tradeReorder$downButton = new Button(bx, by + 22, 42, 20,
                Component.literal("Down"), b -> tradeReorder$move(1));
        this.addRenderableWidget(tradeReorder$upButton);
        this.addRenderableWidget(tradeReorder$downButton);

        // Shares the left-side slot with Up/Down: only shown in View All, while
        // Up/Down are only shown in Reorder, so the two never overlap.
        tradeReorder$cycleButton = new Button(bx, by, 42, 20,
                Component.literal("Cycle"), b -> tradeReorder$cycle());
        this.addRenderableWidget(tradeReorder$cycleButton);
        tradeReorder$refresh();
    }

    @Unique
    private Component tradeReorder$label() {
        return switch (tradeReorder$mode) {
            case TRADE -> Component.literal("Mode: Trade");
            case REORDER -> Component.literal("Mode: Reorder");
            case VIEW_ALL -> Component.literal("Mode: View All");
        };
    }

    @Unique
    private void tradeReorder$cycleMode() {
        tradeReorder$mode = switch (tradeReorder$mode) {
            case TRADE -> Mode.REORDER;
            case REORDER -> Mode.VIEW_ALL;
            case VIEW_ALL -> Mode.TRADE;
        };
    }

    @Unique
    private int tradeReorder$count() {
        MerchantOffers o = tradeReorder$offers();
        return o == null ? 0 : o.size();
    }

    @Unique
    private int tradeReorder$displayCount() {
        MerchantOffers o = tradeReorder$displayOffers();
        return o == null ? 0 : o.size();
    }

    /** Capture original order and apply any saved display order once offers exist. */
    @Unique
    private void tradeReorder$tryInit() {
        MerchantOffers offers = tradeReorder$offers();
        if (offers == null || offers.isEmpty()) {
            return;
        }
        // Re-run init whenever the offer content changes (e.g. the server re-rolled
        // the trades via Cycle). Reordering only permutes the list, so the
        // order-independent signature is stable across moves/reset/restock and
        // we don't needlessly recapture during normal use.
        String signature = tradeReorder$contentSignature(offers);
        if (tradeReorder$inited && signature.equals(tradeReorder$initSignature)) {
            return;
        }
        tradeReorder$inited = true;
        tradeReorder$initSignature = signature;
        if (tradeReorder$selectedRow >= offers.size()) {
            tradeReorder$selectedRow = offers.isEmpty() ? -1 : 0;
        }

        // Record the server's order first; this is what packet indices refer to.
        // Freshly-arrived offers are in server order, so capturing now is correct.
        tradeReorder$original.clear();
        for (MerchantOffer o : offers) {
            tradeReorder$original.add(TradeKeys.fingerprint(o));
        }

        List<String> saved = OrderStore.get().getOrder(tradeReorder$key);
        if (saved == null || saved.isEmpty()) {
            return;
        }
        List<MerchantOffer> remaining = new ArrayList<>(offers);
        List<MerchantOffer> ordered = new ArrayList<>(remaining.size());
        for (String fp : saved) {
            for (int i = 0; i < remaining.size(); i++) {
                if (TradeKeys.fingerprint(remaining.get(i)).equals(fp)) {
                    ordered.add(remaining.remove(i));
                    break;
                }
            }
        }
        ordered.addAll(remaining);
        offers.clear();
        offers.addAll(ordered);
    }

    /**
     * Order-independent signature of an offer list's contents: the sorted set of
     * fingerprints. Unchanged by reordering (which only permutes the list), but
     * differs when the underlying trades themselves change (a Cycle re-roll).
     */
    @Unique
    private String tradeReorder$contentSignature(MerchantOffers offers) {
        List<String> fingerprints = new ArrayList<>(offers.size());
        for (MerchantOffer o : offers) {
            fingerprints.add(TradeKeys.fingerprint(o));
        }
        Collections.sort(fingerprints);
        return String.join("\n", fingerprints);
    }

    @Unique
    private void tradeReorder$refresh() {
        boolean on = tradeReorder$mode == Mode.REORDER;
        ClientMerchantMenuDuck.of(this.menu).tradeReorder$setShowAllTrades(
                tradeReorder$mode == Mode.VIEW_ALL);
        // Leaving View All shrinks the display list from current+future back to
        // just the current offers. Vanilla only re-clamps scrollOff on scroll, so
        // a value left over from the longer list would push every visible row past
        // the end of the (shorter) list; syncTradeRowActivity then treats those
        // rows as "no current offer" and greys out the real, unlocked trades.
        // Clamp scrollOff back into range here, mirroring vanilla mouseScrolled.
        int maxScroll = Math.max(0, tradeReorder$displayCount() - 7);
        if (this.scrollOff > maxScroll) {
            this.scrollOff = maxScroll;
        }
        if (on && tradeReorder$selectedRow < 0 && tradeReorder$count() > 0) {
            tradeReorder$selectedRow = 0;
        }
        int size = tradeReorder$count();
        if (tradeReorder$upButton != null) {
            tradeReorder$upButton.visible = on;
            tradeReorder$upButton.active = on && tradeReorder$selectedRow > 0;
        }
        if (tradeReorder$downButton != null) {
            tradeReorder$downButton.visible = on;
            tradeReorder$downButton.active =
                    on && tradeReorder$selectedRow >= 0 && tradeReorder$selectedRow < size - 1;
        }
        if (tradeReorder$cycleButton != null) {
            boolean viewAll = tradeReorder$mode == Mode.VIEW_ALL;
            tradeReorder$cycleButton.visible = viewAll;
            tradeReorder$cycleButton.active = viewAll && tradeReorder$canCycle();
        }
    }

    /**
     * A villager may have its trades refreshed only if it has never been traded
     * with — zero XP — and is still at the lowest level. Both values are synced
     * to the client by the merchant offers packet. Wandering traders never pass
     * the server-side villager check, so a click on them is simply a no-op.
     */
    @Unique
    private boolean tradeReorder$canCycle() {
        // showProgressBar() is true for villagers and false for wandering traders,
        // so this also keeps the button disabled for traders the server would reject.
        return this.menu.showProgressBar()
                && this.menu.getTraderXp() == 0
                && this.menu.getTraderLevel() <= VillagerData.MIN_VILLAGER_LEVEL;
    }

    @Unique
    private void tradeReorder$cycle() {
        if (tradeReorder$mode != Mode.VIEW_ALL || !tradeReorder$canCycle()) {
            return;
        }
        ClientPlayNetworking.send(ServerboundCycleTradesPayload.PACKET_ID, PacketByteBufs.empty());
    }

    @Unique
    private void tradeReorder$move(int delta) {
        MerchantOffers offers = tradeReorder$offers();
        if (offers == null) {
            return;
        }
        int from = tradeReorder$selectedRow;
        int to = from + delta;
        int n = offers.size();
        if (from < 0 || from >= n || to < 0 || to >= n) {
            return;
        }
        List<MerchantOffer> list = new ArrayList<>(offers);
        MerchantOffer moved = list.remove(from);
        list.add(to, moved);
        offers.clear();
        offers.addAll(list);

        // Persist the new display order.
        List<String> order = new ArrayList<>(offers.size());
        for (MerchantOffer o : offers) {
            order.add(TradeKeys.fingerprint(o));
        }
        OrderStore.get().putOrder(tradeReorder$key, order);

        tradeReorder$selectedRow = to;
        tradeReorder$refresh();
    }

    /**
     * Restore the merchant's offers to the server's original order and forget any
     * saved ordering for this merchant. Safe to call any time after offers load.
     */
    @Unique
    private void tradeReorder$reset() {
        MerchantOffers offers = tradeReorder$offers();
        if (offers == null || tradeReorder$original.isEmpty()) {
            return;
        }
        // Rebuild the list in original fingerprint order.
        List<MerchantOffer> remaining = new ArrayList<>(offers);
        List<MerchantOffer> ordered = new ArrayList<>(remaining.size());
        for (String fp : tradeReorder$original) {
            for (int i = 0; i < remaining.size(); i++) {
                if (TradeKeys.fingerprint(remaining.get(i)).equals(fp)) {
                    ordered.add(remaining.remove(i));
                    break;
                }
            }
        }
        ordered.addAll(remaining);
        offers.clear();
        offers.addAll(ordered);

        OrderStore.get().clear(tradeReorder$key);
        tradeReorder$selectedRow = tradeReorder$count() > 0 ? 0 : -1;
        tradeReorder$refresh();
    }

    /**
     * Translate a current display index into the index the server expects.
     * The offer shown at display row {@code displayRow} has a fingerprint; we
     * return that fingerprint's position in the captured original (server) order.
     */
    @Unique
    private int tradeReorder$realIndex(int displayRow) {
        MerchantOffers offers = tradeReorder$offers();
        if (offers == null || displayRow < 0 || displayRow >= offers.size()) {
            return displayRow;
        }
        String fp = TradeKeys.fingerprint(offers.get(displayRow));
        int idx = tradeReorder$original.indexOf(fp);
        return idx >= 0 ? idx : displayRow;
    }

    /**
     * The vanilla press handler sets {@code shopItem = button.getIndex() + scrollOff},
     * a <em>display</em> index into our reordered client list, and postButtonClick()
     * consumes it three ways: {@code setSelectionHint} and {@code tryMoveItems} (which
     * read the client's reordered offer list to drive the result-slot preview and fill
     * the payment slots), plus the outgoing {@code ServerboundSelectTradePacket}. The
     * out-of-stock and progress-bar overlays also key off the {@code shopItem} field.
     *
     * <p>Only the packet must carry the <em>server</em> index, because the server's
     * offer list is never reordered. So we leave {@code shopItem} as the display index
     * — keeping the local preview, payment-slot fill and overlays aligned with what the
     * player actually sees — and translate just the packet argument to the clicked
     * offer's original server index. (Rewriting the field instead, as a HEAD inject
     * once did, made the result square preview the wrong trade.)</p>
     */
    @ModifyArg(
            method = "postButtonClick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/ServerboundSelectTradePacket;<init>(I)V"))
    private int tradeReorder$translatePacketIndex(int displayIndex) {
        return tradeReorder$original.isEmpty() ? displayIndex : tradeReorder$realIndex(displayIndex);
    }

    /**
     * While reorder mode is ON, a left-click on a trade row selects that row for
     * moving (instead of executing the trade). The clicked display row is found
     * from the on-screen trade buttons; {@code scrollOff} maps the visible button
     * index to the absolute row. We cancel vanilla handling so editing a list
     * never accidentally performs a trade.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void tradeReorder$onClick(double mouseX, double mouseY, int button,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || this.tradeOfferButtons == null
                || tradeReorder$mode == Mode.TRADE) {
            return;
        }
        double mx = mouseX;
        double my = mouseY;
        for (int i = 0; i < this.tradeOfferButtons.length; i++) {
            MerchantScreen.TradeOfferButton btn = this.tradeOfferButtons[i];
            if (btn == null || !btn.visible) {
                continue;
            }
            if (mx >= btn.x && mx < btn.x + btn.getWidth()
                    && my >= btn.y && my < btn.y + btn.getHeight()) {
                int row = this.scrollOff + i;
                if (tradeReorder$mode == Mode.REORDER && row >= 0 && row < tradeReorder$count()) {
                    tradeReorder$selectedRow = row;
                    tradeReorder$refresh();
                }
                if (row >= 0 && row < tradeReorder$displayCount()) {
                    cir.setReturnValue(true);   // consume the click; don't trade
                }
                return;
            }
        }
    }

    // Target render (declared on MerchantScreen itself, runs every frame); it is
    // this version's counterpart of 26.x's extractContents.
    @Inject(method = "render", at = @At("HEAD"))
    private void tradeReorder$beforeExtract(PoseStack poseStack, int mouseX, int mouseY,
                                            float delta, CallbackInfo ci) {
        ClientMerchantMenuDuck.of(this.menu).tradeReorder$setShowAllTrades(
                tradeReorder$mode == Mode.VIEW_ALL);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void tradeReorder$onExtract(PoseStack poseStack, int mouseX, int mouseY,
                                        float delta, CallbackInfo ci) {
        tradeReorder$tryInit();

        // NOTE: we intentionally do NOT rewrite TradeOfferButton.index here.
        // The backing MerchantOffers list is physically reordered (see
        // tradeReorder$tryInit and tradeReorder$move), so display row i already
        // maps to offer i and the button's natural captured index selects the
        // correct offer. Rewriting the final 'index' field was both redundant
        // and required a fragile 'mutable' class-tweak that fails at runtime
        // with IllegalAccessError on Loader 0.18.

        tradeReorder$syncTradeRowActivity();

        if (tradeReorder$mode == Mode.REORDER) {
            tradeReorder$refresh();
            int size = tradeReorder$count();
            String label = tradeReorder$selectedRow >= 0
                    ? ("#" + (tradeReorder$selectedRow + 1) + "/" + size)
                    : ("/" + size);
            this.font.drawShadow(poseStack, label, this.leftPos - 46, this.topPos + 64,
                    0xFFFFFFFF);
        }
    }

    @Unique
    private void tradeReorder$syncTradeRowActivity() {
        if (this.tradeOfferButtons == null) {
            return;
        }
        int currentSize = tradeReorder$count();
        int displaySize = tradeReorder$displayCount();
        for (int i = 0; i < this.tradeOfferButtons.length; i++) {
            MerchantScreen.TradeOfferButton btn = this.tradeOfferButtons[i];
            if (btn == null) {
                continue;
            }
            int row = this.scrollOff + i;
            boolean currentOffer = row >= 0 && row < currentSize;
            boolean futureOffer = tradeReorder$mode == Mode.VIEW_ALL
                    && row >= currentSize && row < displaySize;
            btn.active = currentOffer && !futureOffer;
        }
    }
}
