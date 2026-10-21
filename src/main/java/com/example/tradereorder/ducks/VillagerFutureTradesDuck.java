package com.example.tradereorder.ducks;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Optional;

public interface VillagerFutureTradesDuck {
    static VillagerFutureTradesDuck of(Villager villager) {
        return (VillagerFutureTradesDuck) villager;
    }

    Optional<MerchantOffers> tradeReorder$getFutureOffers();

    void tradeReorder$sendFutureOffers(ServerPlayer player);

    /**
     * Whether this villager's trades may be refreshed: only true for a villager
     * that has never been traded with (zero XP), still at the lowest level, and
     * already holding generated offers.
     */
    boolean tradeReorder$canCycleTrades();

    /**
     * Re-roll this villager's current trades (and regenerate its future tiers),
     * then push both to the trading player. No-op unless {@link #tradeReorder$canCycleTrades()}.
     */
    void tradeReorder$cycleTrades(ServerPlayer player);
}
