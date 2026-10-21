package com.example.tradereorder.network;

import com.example.tradereorder.TradeReorder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Optional;

// Pre-1.20.5 Fabric networking has no payload types: the packet is a channel id
// plus a hand-written buffer encoding.
public record ClientboundFutureTradesPayload(Optional<MerchantOffers> offers) {

    public static final ResourceLocation PACKET_ID = TradeReorder.id("future_trades");

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(offers().isPresent());
        offers().ifPresent(offers -> offers.writeToStream(buf));
    }

    public static ClientboundFutureTradesPayload read(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return new ClientboundFutureTradesPayload(Optional.empty());
        }
        return new ClientboundFutureTradesPayload(
                Optional.of(MerchantOffers.createFromStream(buf)));
    }

    public ClientboundFutureTradesPayload(MerchantOffers offers) {
        this(Optional.ofNullable(offers));
    }
}
