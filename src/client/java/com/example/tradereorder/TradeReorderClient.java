package com.example.tradereorder;

import com.example.tradereorder.network.ClientboundFutureTradesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;

public class TradeReorderClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Load any saved orderings from disk into memory.
        OrderStore.get().load();
        ClientPlayNetworking.registerGlobalReceiver(
                ClientboundFutureTradesPayload.PACKET_ID,
                (payload, context) -> {
                    if (!(Minecraft.getInstance().screen instanceof MerchantScreen screen)) {
                        return;
                    }
                    ClientMerchantMenuDuck.of(screen.getMenu())
                            .tradeReorder$setFutureOffers(payload.offers());
                });
        TradeReorder.LOGGER.info("[Trade Reorder] client initialized");
    }
}
