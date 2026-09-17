package com.reazip.economycraft.bounties;

import com.reazip.economycraft.api.v1.EconomyCraftApi;
import com.reazip.economycraft.bounties.BountyPermissions.Nodes;
import com.reazip.economycraft.bounties.MenuSupport.ClickKind;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

public final class BountyUi {
    private static final int[] TOP_SLOTS = {2, 3, 4, 5, 6, 11, 12, 13, 14, 15};
    private static final int EMPTY_SLOT = 13;
    private static final int ADMIN_SLOT = 26;

    private BountyUi() {
    }

    public static void open(ServerPlayer player) {
        if (!BountyPermissions.checkCommand(player.createCommandSourceStack(), Nodes.COMMAND_BOUNTY)) {
            MenuSupport.denyPermission(player);
            return;
        }
        MinecraftServer server = player.level().getServer();
        List<Bounty> bounties = EconomyCraftBounties.service(server).activeBounties();
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new BountyListMenu(id, inv, player, server, bounties),
                Component.literal("Active Bounties")));
    }

    private static void render(SimpleContainer container, ServerPlayer viewer, MinecraftServer server,
                               List<Bounty> bounties) {
        EconomyCraftApi api = EconomyCraftApi.get(server);
        int limit = Math.min(TOP_SLOTS.length, bounties.size());
        for (int i = 0; i < limit; i++) {
            container.setItem(TOP_SLOTS[i], entryItem(server, api, bounties.get(i), i + 1));
        }
        if (bounties.isEmpty()) {
            container.setItem(EMPTY_SLOT, MenuSupport.button(Items.BARRIER, "No active bounties", ChatFormatting.YELLOW));
        }
        if (BountyPermissions.hasAnyAdmin(viewer.createCommandSourceStack())) {
            container.setItem(ADMIN_SLOT, MenuSupport.button(Items.COMMAND_BLOCK, "Admin", ChatFormatting.LIGHT_PURPLE,
                    MenuSupport.hint("Edit the bounty settings."),
                    MenuSupport.hint("Only admins can see this.")));
        }
        MenuSupport.fillBackground(container);
    }

    private static ItemStack entryItem(MinecraftServer server, EconomyCraftApi api, Bounty bounty, int rank) {
        int contributors = bounty.contributions().size();
        Component amountLore = Component.literal("Bounty: ")
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD))
                .append(Component.literal(api.formatMoney(bounty.total()))
                        .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GREEN)));
        ItemStack head = MenuSupport.button(headFor(server, bounty.targetId(), bounty.targetName()),
                "#" + rank + " " + bounty.targetName(), rank == 1 ? ChatFormatting.GOLD : ChatFormatting.YELLOW,
                amountLore, MenuSupport.hint(contributors + " contributor" + (contributors == 1 ? "" : "s")));
        head.setCount(Math.min(64, rank));
        return head;
    }

    private static ItemStack headFor(MinecraftServer server, UUID playerId, String name) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        var profile = online != null
                ? ProfileComponentCompat.tryResolvedOrUnresolved(online.getGameProfile())
                : ProfileComponentCompat.tryUnresolved(name);
        profile.ifPresent(resolvable -> head.set(DataComponents.PROFILE, resolvable));
        return head;
    }

    private static final class BountyListMenu extends CompatMenu {
        private final ServerPlayer viewer;

        private BountyListMenu(int id, Inventory inv, ServerPlayer viewer, MinecraftServer server, List<Bounty> bounties) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            SimpleContainer container = new SimpleContainer(MenuSupport.GRID_SIZE);
            render(container, viewer, server, bounties);

            for (Slot slot : MenuSupport.chestSlots(container, inv)) addSlot(slot);
        }

        @Override
        protected boolean onClick(int slot, ClickKind kind) {
            if (slot < 0 || slot >= MenuSupport.GRID_SIZE) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;

            if (slot == ADMIN_SLOT && BountyPermissions.hasAnyAdmin(viewer.createCommandSourceStack())) {
                MenuSupport.click(viewer);
                BountyAdminUi.open(viewer);
            }
            return true;
        }
    }
}
