package com.example.tradereorder.test;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.lang.reflect.Modifier;

/** Runtime regression check; this test mod is never included in the release jar. */
public final class MixinSmokeTest implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            ClassLoader loader = getClass().getClassLoader();
            // Resolve every class targeted by the release mod's mixins. Fabric
            // applies and validates shadows, accessors and injections on load.
            for (String name : new String[] {
                    "net.minecraft.world.item.trading.Merchant",
                    "net.minecraft.world.entity.npc.Villager",
                    "net.minecraft.world.inventory.MerchantMenu",
                    "net.minecraft.client.gui.screens.inventory.MerchantScreen"
            }) {
                Class<?> target = Class.forName(name, false, loader);
                boolean transformed = java.util.Arrays.stream(target.getDeclaredMethods())
                        .anyMatch(method -> method.getName().contains("tradeReorder$"));
                if (!transformed) {
                    throw new AssertionError("Mod mixin was not applied to " + name);
                }
                System.out.println("Mixin smoke test loaded: " + name);
            }
            Class<?> tradeButton = Class.forName(
                    "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton", false, loader);
            if (!Modifier.isPublic(tradeButton.getModifiers())) {
                throw new AssertionError("TradeOfferButton class tweaker was not applied");
            }
            Class.forName("net.minecraft.SharedConstants", true, loader)
                    .getMethod("tryDetectVersion").invoke(null);
            Class.forName("net.minecraft.server.Bootstrap", true, loader)
                    .getMethod("bootStrap").invoke(null);
            System.out.println("MIXIN_SMOKE_TEST_PASSED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
