package com.example.tradereorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Registry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.Vec3;

/**
 * Stable identifiers used for persistence.
 */
public final class TradeKeys {

    private TradeKeys() {}

    /**
     * A per-merchant key, used to look up that specific villager's saved order.
     *
     * <p>We must key on the entity UUID so each villager is independent. The
     * tricky part on the client is identifying <em>which</em> merchant entity the
     * open screen belongs to: the menu only holds a client-side merchant wrapper
     * (no UUID), and {@code getTradingPlayer()} is not reliably synced to the
     * client. So we resolve the entity with a cascade:</p>
     *
     * <ol>
     *   <li>An entity whose {@code getTradingPlayer()} is this player (works when
     *       the server happens to have synced it).</li>
     *   <li>Otherwise the {@link Merchant} entity the player is most directly
     *       looking at, within a few blocks — this is the villager they just
     *       right-clicked to open the screen.</li>
     * </ol>
     *
     * <p>If neither resolves we return a non-persistent, per-open key
     * ({@code transient:...}). That key is unique each time, so an unresolved
     * merchant can never overwrite another villager's saved order — which was the
     * old bug, where every same-profession villager collided on a shared
     * {@code title:Farmer}-style key.</p>
     */
    public static String merchantKey(String fallbackTitle) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return "transient:" + System.nanoTime();
        }

        // 1) Exact: an entity that reports trading with us.
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Merchant merchant && merchant.getTradingPlayer() == mc.player) {
                return worldScope() + "|villager:" + e.getUUID();
            }
        }

        // 2) Best-effort: the merchant entity the player is looking at, nearest first.
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Entity best = null;
        double bestScore = -1.0;          // higher = more directly in front
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Merchant) || !e.isAlive()) {
                continue;
            }
            Vec3 toEntity = e.getEyePosition().subtract(eye);
            double dist = toEntity.length();
            if (dist > 8.0 || dist < 1.0e-4) {     // interaction-ish range
                continue;
            }
            double aligned = look.dot(toEntity.scale(1.0 / dist)); // cosine of angle
            if (aligned < 0.5) {                   // must be roughly in front (~60°)
                continue;
            }
            // Prefer the most centered-on-screen merchant; break ties by closeness.
            double score = aligned - dist * 0.01;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best != null) {
            return worldScope() + "|villager:" + best.getUUID();
        }

        // 3) Could not resolve an entity: use a unique, non-persistent key so we
        //    never clobber a real villager's saved order.
        return "transient:" + System.nanoTime() + ":" + fallbackTitle;
    }

    /**
     * A stable identifier for the current world/server, so the same villager UUID
     * in two different worlds (e.g. a copied save, which preserves entity UUIDs)
     * does not collide in the global orders file.
     *
     * <ul>
     *   <li>Singleplayer: the save folder name (unique per world on disk).</li>
     *   <li>Multiplayer: the server address.</li>
     *   <li>Unknown: a constant fallback (better to group than to misattribute).</li>
     * </ul>
     */
    public static String worldScope() {
        Minecraft mc = Minecraft.getInstance();

        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            try {
                java.io.File dir = sp.getServerDirectory();   // a File before 1.20.5
                java.nio.file.Path name = dir == null ? null : dir.toPath().getFileName();
                if (name != null) {
                    return "sp:" + name;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }

        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isEmpty()) {
            return "mp:" + server.ip;
        }

        return "world:unknown";
    }

    /**
     * Content fingerprint for an offer: costA + costB + result, by item id and
     * count. Independent of list position, so a saved order stays meaningful
     * even if trades are added/removed/re-leveled.
     */
    public static String fingerprint(MerchantOffer offer) {
        return stackToken(offer.getCostA())
                + "|" + stackToken(offer.getCostB())
                + "|" + stackToken(offer.getResult());
    }

    private static String stackToken(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "_";
        }
        // getKey(...) returns the registry id; its string form is "namespace:path".
        // Using String.valueOf avoids depending on the id class name, which the
        // 26.1 registry refactor may have changed.
        String id = String.valueOf(Registry.ITEM.getKey(s.getItem()));
        return id + "x" + s.getCount();
    }
}
