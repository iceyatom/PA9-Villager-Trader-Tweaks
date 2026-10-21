package com.example.tradereorder.mixin;

import com.example.tradereorder.ducks.VillagerFutureTradesDuck;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Merchant.class)
public interface MerchantMixin {

    @Inject(method = "openTradingScreen", at = @At("TAIL"))
    private void tradeReorder$sendFutureTrades(Player player,
                                               net.minecraft.network.chat.Component displayName,
                                               int level,
                                               CallbackInfo ci) {
        if (!((Merchant) this instanceof Villager villager)) {
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        VillagerFutureTradesDuck.of(villager).tradeReorder$sendFutureOffers(serverPlayer);
    }
}
