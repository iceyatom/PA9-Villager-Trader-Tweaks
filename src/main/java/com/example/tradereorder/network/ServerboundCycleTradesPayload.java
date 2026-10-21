package com.example.tradereorder.network;

import com.example.tradereorder.TradeReorder;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent by the client when the player presses the <em>Cycle</em> button in
 * View All mode. Carries no data — the server resolves the target villager from
 * the player's open merchant menu and re-rolls its trades if it is still fresh
 * (never traded with, lowest level, zero XP).
 */
public record ServerboundCycleTradesPayload() {

    // Pre-1.20.5 Fabric networking: an empty packet on this channel.
    public static final ResourceLocation PACKET_ID = TradeReorder.id("cycle_trades");
}
