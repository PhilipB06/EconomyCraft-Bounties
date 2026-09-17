package com.reazip.economycraft.bounties;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

public final class EconomyCraftBounties {
    public static final String MOD_ID = "economycraft_bounties";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static BountyService service;
    private static MinecraftServer activeServer;

    private EconomyCraftBounties() {
    }

    public static void onServerStarting(MinecraftServer server) {
        BountyConfig config = BountyConfig.load(server);
        BountyStore store = new BountyStore(BountyFiles.dataFile(server));
        service = new BountyService(server, config, store, store.load());
        activeServer = server;
        LOGGER.info("[EconomyCraft Bounties] Loaded {} active bounties.", service.size());
    }

    public static void onServerStopping(MinecraftServer server) {
        if (service != null && activeServer == server) {
            service.save();
            service = null;
            activeServer = null;
        }
    }

    public static BountyService service(MinecraftServer server) {
        BountyService active = serviceOrNull(server);
        if (active == null) {
            throw new IllegalStateException("EconomyCraft Bounties is not initialized for this server");
        }
        return active;
    }

    private static BountyService serviceOrNull(MinecraftServer server) {
        return service != null && activeServer == server ? service : null;
    }

    public static void onPlayerKilled(ServerPlayer victim, Entity damageSource) {
        if (!(damageSource instanceof ServerPlayer killer)) return;
        if (victim.getUUID().equals(killer.getUUID())) return;
        BountyService active = serviceOrNull(victim.level().getServer());
        if (active == null) return;
        try {
            active.processPlayerKill(victim, killer);
        } catch (Throwable failure) {
            LOGGER.error("[EconomyCraft Bounties] Failed to process the kill of {} by {}.",
                    victim.getUUID(), killer.getUUID(), failure);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        BountyService active = serviceOrNull(server);
        if (active == null) return;
        try {
            active.flushPendingSave();
        } catch (Throwable failure) {
            LOGGER.error("[EconomyCraft Bounties] Failed to flush pending bounty data.", failure);
        }
    }
}
