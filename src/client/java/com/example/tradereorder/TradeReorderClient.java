package com.example.tradereorder;

import com.example.tradereorder.network.ClientboundFutureTradesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;

public class TradeReorderClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Load any saved orderings from disk into memory.
        OrderStore.get().load();
        // Pre-1.20.5 Fabric networking: decode on the network thread, then apply
        // on the client thread (where the 1.20.5+ payload API runs its handlers).
        ClientPlayNetworking.registerGlobalReceiver(
                ClientboundFutureTradesPayload.PACKET_ID,
                (client, handler, buf, responseSender) -> {
                    ClientboundFutureTradesPayload payload = ClientboundFutureTradesPayload.read(buf);
                    client.execute(() -> {
                        if (!(client.screen instanceof MerchantScreen screen)) {
                            return;
                        }
                        ClientMerchantMenuDuck.of(screen.getMenu())
                                .tradeReorder$setFutureOffers(payload.offers());
                    });
                });
        TradeReorder.LOGGER.info("[Trade Reorder] client initialized");
    }
}
