package com.reazip.economycraft.bounties;

import com.reazip.economycraft.bounties.MenuSupport.ClickKind;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

abstract class CompatMenu extends AbstractContainerMenu {
    protected CompatMenu(MenuType<?> type, int id) {
        super(type, id);
    }

    @Override
    public void clicked(int slot, int dragType, ClickType type, Player player) {
        ClickKind kind = switch (type) {
            case PICKUP -> ClickKind.PICKUP;
            case QUICK_MOVE -> ClickKind.QUICK_MOVE;
            default -> ClickKind.OTHER;
        };
        if (onClick(slot, kind)) return;
        super.clicked(slot, dragType, type, player);
    }

    protected abstract boolean onClick(int slot, ClickKind kind);

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
