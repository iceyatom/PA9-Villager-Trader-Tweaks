package com.example.tradereorder.mixin;

import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Server-side access to the merchant backing an open {@link MerchantMenu}, so a
 * cycle-trades request can resolve the villager the player is trading with.
 */
@Mixin(MerchantMenu.class)
public interface MerchantMenuTraderAccessor {

    @Accessor("trader")
    Merchant tradeReorder$getTrader();

    // 26.3 has MerchantMenu.updateSellItem(), which just calls
    // tradeContainer.updateSellItem(); 26.1 needs the container itself.
    @Accessor("tradeContainer")
    MerchantContainer tradeReorder$getTradeContainer();
}
