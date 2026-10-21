package com.example.tradereorder;

import com.example.tradereorder.ducks.VillagerFutureTradesDuck;
import com.example.tradereorder.mixin.MerchantMenuTraderAccessor;
import com.example.tradereorder.network.ServerboundCycleTradesPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TradeReorder implements ModInitializer {
    public static final String MOD_ID = "trade-reorder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Pre-1.20.5 Fabric networking: channels need no payload type registration,
        // and receivers run on the network thread, so hop to the server thread
        // (where the 1.20.5+ payload API runs its handlers).
        ServerPlayNetworking.registerGlobalReceiver(
                ServerboundCycleTradesPayload.PACKET_ID,
                (server, player, handler, buf, responseSender) -> server.execute(() -> {
                    if (!(player.containerMenu instanceof MerchantMenu menu)) {
                        return;
                    }
                    Merchant trader = ((MerchantMenuTraderAccessor) menu).tradeReorder$getTrader();
                    if (trader instanceof Villager villager) {
                        VillagerFutureTradesDuck.of(villager).tradeReorder$cycleTrades(player);
                    }
                }));
        LOGGER.info("[Trade Reorder] initialized");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
