package com.reazip.economycraft.bounties.fabric;

import com.reazip.economycraft.bounties.BountyCommands;
import com.reazip.economycraft.bounties.BountyPermissions;
import com.reazip.economycraft.bounties.EconomyCraftBounties;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class EconomyCraftBountiesFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        installPermissionBackend();
        ServerLifecycleEvents.SERVER_STARTING.register(EconomyCraftBounties::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(EconomyCraftBounties::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(EconomyCraftBounties::onServerTick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BountyCommands.register(dispatcher));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer victim) {
                EconomyCraftBounties.onPlayerKilled(victim, damageSource.getEntity());
            }
        });
    }

    private static void installPermissionBackend() {
        MethodHandle check = resolvePermissionsApiCheck();
        if (check == null) return;
        BountyPermissions.setBackend((source, node, fallback) -> {
            try {
                return (boolean) check.invoke(source, node, fallback);
            } catch (Throwable t) {
                return fallback;
            }
        });
    }

    private static MethodHandle resolvePermissionsApiCheck() {
        try {
            Class<?> permissionsClass = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            return MethodHandles.publicLookup().findStatic(permissionsClass, "check",
                    MethodType.methodType(boolean.class, SharedSuggestionProvider.class, String.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
