package com.reazip.economycraft.bounties.neoforge;

import com.reazip.economycraft.bounties.BountyCommands;
import com.reazip.economycraft.bounties.BountyPermissions;
import com.reazip.economycraft.bounties.BountyPermissions.Nodes;
import com.reazip.economycraft.bounties.EconomyCraftBounties;
import com.reazip.economycraft.bounties.PermissionCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.HashMap;
import java.util.Map;

@Mod(EconomyCraftBounties.MOD_ID)
public final class EconomyCraftBountiesNeoForge {
    private static final Map<String, PermissionNode<Boolean>> PERMISSION_NODES = new HashMap<>();

    public EconomyCraftBountiesNeoForge() {
        NeoForge.EVENT_BUS.register(this);
        installPermissionBackend();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        EconomyCraftBounties.onServerStarting(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        EconomyCraftBounties.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        EconomyCraftBounties.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BountyCommands.register(event.getDispatcher());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        Entity damageSource = event.getSource().getEntity();
        EconomyCraftBounties.onPlayerKilled(victim, damageSource);
    }

    @SubscribeEvent
    public void onGatherPermissionNodes(PermissionGatherEvent.Nodes event) {
        registerPermissionNode(event, Nodes.ADMIN, (player, uuid, ctx) -> player != null && isOperator(player));
        for (String node : Nodes.ADMIN_ALL) {
            if (node.equals(Nodes.ADMIN)) continue;
            registerPermissionNode(event, node, (player, uuid, ctx) -> player != null && hasBlanketAdmin(player));
        }
        for (String node : Nodes.COMMAND_ALL) {
            registerPermissionNode(event, node, (player, uuid, ctx) -> true);
        }
    }

    private static void registerPermissionNode(PermissionGatherEvent.Nodes event, String fullNode,
                                               PermissionNode.PermissionResolver<Boolean> resolver) {
        int dot = fullNode.indexOf('.');
        String modId = fullNode.substring(0, dot);
        String path = fullNode.substring(dot + 1);
        PermissionNode<Boolean> node = new PermissionNode<>(modId, path, PermissionTypes.BOOLEAN, resolver);
        PERMISSION_NODES.put(fullNode, node);
        event.addNodes(node);
    }

    private static boolean isOperator(ServerPlayer player) {
        return PermissionCompat.isOperator(player.createCommandSourceStack());
    }

    private static boolean hasBlanketAdmin(ServerPlayer player) {
        PermissionNode<Boolean> adminNode = PERMISSION_NODES.get(Nodes.ADMIN);
        Boolean result = adminNode != null ? PermissionAPI.getPermission(player, adminNode) : null;
        return result != null ? result : isOperator(player);
    }

    private static void installPermissionBackend() {
        BountyPermissions.setBackend((source, node, fallback) -> {
            PermissionNode<Boolean> permissionNode = PERMISSION_NODES.get(node);
            ServerPlayer player = source.getPlayer();
            if (permissionNode == null || player == null) return fallback;
            try {
                Boolean result = PermissionAPI.getPermission(player, permissionNode);
                return result != null ? result : fallback;
            } catch (Throwable t) {
                return fallback;
            }
        });
    }
}
