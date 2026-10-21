package com.example.tradereorder.mixin;

import com.example.tradereorder.ducks.VillagerFutureTradesDuck;
import com.example.tradereorder.network.ClientboundFutureTradesPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ReputationEventHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.world.entity.npc.villager.VillagerTrades;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(Villager.class)
public abstract class VillagerMixin extends AbstractVillager
        implements ReputationEventHandler, VillagerDataHolder, VillagerFutureTradesDuck {

    @Shadow public abstract VillagerData getVillagerData();

    @Shadow public abstract int getVillagerXp();

    @Shadow private void resetSpecialPrices() {
        throw new AssertionError("shadow");
    }

    @Shadow private void updateSpecialPrices(Player player) {
        throw new AssertionError("shadow");
    }

    @Unique private static final String TRADE_REORDER_FUTURE_OFFERS = "TradeReorderFutureOffers";

    @Unique private List<MerchantOffers> tradeReorder$futureOffers;

    protected VillagerMixin(EntityType<? extends AbstractVillager> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("HEAD"))
    private void tradeReorder$saveFutureTrades(ValueOutput output, CallbackInfo ci) {
        if (tradeReorder$futureOffers != null && !tradeReorder$futureOffers.isEmpty()) {
            output.store(TRADE_REORDER_FUTURE_OFFERS,
                    MerchantOffers.CODEC.listOf(),
                    tradeReorder$futureOffers);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void tradeReorder$readFutureTrades(ValueInput input, CallbackInfo ci) {
        Optional<List<MerchantOffers>> saved =
                input.read(TRADE_REORDER_FUTURE_OFFERS, MerchantOffers.CODEC.listOf());
        tradeReorder$futureOffers = saved.map(ArrayList::new).orElse(null);
    }

    @Inject(method = "updateTrades", at = @At("HEAD"), cancellable = true)
    private void tradeReorder$useStoredFutureTradeSet(ServerLevel level, CallbackInfo ci) {
        if (this.offers == null || this.offers.isEmpty()) {
            tradeReorder$futureOffers = null;
            return;
        }
        if (tradeReorder$futureOffers == null || tradeReorder$futureOffers.isEmpty()) {
            return;
        }

        MerchantOffers nextOffers = tradeReorder$futureOffers.remove(0);
        this.offers.addAll(nextOffers);
        tradeReorder$trimFutureOffers();
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void tradeReorder$trimInvalidFutureTrades(CallbackInfo ci) {
        if (this.offers == null) {
            tradeReorder$futureOffers = null;
            return;
        }
        tradeReorder$trimFutureOffers();
    }

    @Override
    public Optional<MerchantOffers> tradeReorder$getFutureOffers() {
        tradeReorder$ensureFutureOffers();
        if (tradeReorder$futureOffers == null || tradeReorder$futureOffers.isEmpty()) {
            return Optional.empty();
        }

        MerchantOffers condensed = new MerchantOffers();
        for (MerchantOffers offers : tradeReorder$futureOffers) {
            condensed.addAll(offers);
        }
        return condensed.isEmpty() ? Optional.empty() : Optional.of(condensed);
    }

    @Override
    public void tradeReorder$sendFutureOffers(ServerPlayer player) {
        Optional<MerchantOffers> offers = tradeReorder$getFutureOffers();
        ServerPlayNetworking.send(player, new ClientboundFutureTradesPayload(offers));
    }

    @Override
    public boolean tradeReorder$canCycleTrades() {
        return getVillagerXp() == 0
                && getVillagerData().level() == VillagerData.MIN_VILLAGER_LEVEL
                && this.offers != null
                && !this.offers.isEmpty();
    }

    @Override
    public void tradeReorder$cycleTrades(ServerPlayer player) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!tradeReorder$canCycleTrades()) {
            return;
        }

        // Wipe the current offers and drop the cached future tiers, then let the
        // vanilla generation path roll a fresh set for the current (lowest) level.
        // Clearing first makes tradeReorder$useStoredFutureTradeSet fall through to
        // vanilla instead of replaying a stored set, so the trades are genuinely re-rolled.
        this.offers = new MerchantOffers();
        tradeReorder$futureOffers = null;
        this.updateTrades(serverLevel);

        // Push the refreshed current offers and regenerated future tiers to the client.
        tradeReorder$updateSpecialPrices(player);
        tradeReorder$sendFutureOffers(player);
    }

    /**
     * What 26.3's {@code Villager.updateSpecialPrices(Player)} does: reset and
     * re-apply the player's special prices, refresh the open merchant menu's sell
     * slot, and send the offers to the trading player. This version's
     * updateSpecialPrices only applies the prices, so the other steps are spelled out.
     */
    @Unique
    private void tradeReorder$updateSpecialPrices(Player player) {
        resetSpecialPrices();
        updateSpecialPrices(player);
        MerchantOffers offers = this.getOffers();
        Player tradingPlayer = this.getTradingPlayer();
        if (tradingPlayer != null && !offers.isEmpty()) {
            if (tradingPlayer.containerMenu instanceof MerchantMenu menu) {
                ((MerchantMenuTraderAccessor) menu).tradeReorder$getTradeContainer().updateSellItem();
            }
            tradingPlayer.sendMerchantOffers(tradingPlayer.containerMenu.containerId, offers,
                    getVillagerData().level(), getVillagerXp(), this.showProgressBar(), this.canRestock());
        }
    }

    @Unique
    private void tradeReorder$ensureFutureOffers() {
        if (tradeReorder$futureOffers != null) {
            return;
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.offers == null || this.offers.isEmpty()) {
            return;
        }

        List<MerchantOffers> generated = new ArrayList<>();
        int level = getVillagerData().level();
        while (level++ < 5) {
            MerchantOffers offers = tradeReorder$generateOffersForLevel(serverLevel, level);
            if (!offers.isEmpty()) {
                generated.add(offers);
            }
        }
        tradeReorder$futureOffers = generated;
        tradeReorder$trimFutureOffers();
    }

    /** Same lookup as this version's vanilla {@code Villager.updateTrades(ServerLevel)}, for another level. */
    @Unique
    private MerchantOffers tradeReorder$generateOffersForLevel(ServerLevel serverLevel, int level) {
        MerchantOffers generated = new MerchantOffers();
        VillagerData data = getVillagerData().withLevel(level);
        ResourceKey<VillagerProfession> profession = data.profession().unwrapKey().orElse(null);
        if (profession == null) {
            return generated;
        }
        Int2ObjectMap<VillagerTrades.ItemListing[]> trades;
        if (serverLevel.enabledFeatures().contains(FeatureFlags.TRADE_REBALANCE)) {
            Int2ObjectMap<VillagerTrades.ItemListing[]> experimental =
                    VillagerTrades.EXPERIMENTAL_TRADES.get(profession);
            trades = experimental != null ? experimental : VillagerTrades.TRADES.get(profession);
        } else {
            trades = VillagerTrades.TRADES.get(profession);
        }
        if (trades != null && !trades.isEmpty()) {
            VillagerTrades.ItemListing[] listings = trades.get(data.level());
            if (listings != null) {
                this.addOffersFromItemListings(serverLevel, generated, listings, 2);
            }
        }
        return generated;
    }

    @Unique
    private void tradeReorder$trimFutureOffers() {
        if (tradeReorder$futureOffers == null) {
            return;
        }
        int requiredSets = Math.max(0, 5 - getVillagerData().level());
        while (tradeReorder$futureOffers.size() > requiredSets) {
            tradeReorder$futureOffers.remove(0);
        }
        if (tradeReorder$futureOffers.isEmpty()) {
            tradeReorder$futureOffers = null;
        }
    }
}
