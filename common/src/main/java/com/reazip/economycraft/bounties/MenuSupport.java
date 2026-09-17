package com.reazip.economycraft.bounties;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

final class MenuSupport {
    static final int GRID_SIZE = 27;
    private static final int SLOT_SIZE = 18;
    private static final int GRID_LEFT = 8;
    private static final int GRID_TOP = 18;
    private static final int INVENTORY_TOP = GRID_TOP + 3 * SLOT_SIZE + 14;

    private static volatile Item grayPane;
    private static volatile Item redPane;
    private static volatile Item limePane;
    private static volatile SoundEvent failureSound;

    private MenuSupport() {
    }

    enum ClickKind {
        PICKUP,
        QUICK_MOVE,
        OTHER
    }

    static ItemStack button(Item item, String name, ChatFormatting color, Component... lore) {
        return button(new ItemStack(item), name, color, lore);
    }

    static ItemStack button(ItemStack stack, String name, ChatFormatting color, Component... lore) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)
                .withStyle(s -> s.withItalic(false).withBold(true).withColor(color)));
        if (lore.length > 0) stack.set(DataComponents.LORE, new ItemLore(List.of(lore)));
        return stack;
    }

    static Component hint(String text) {
        return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY));
    }

    static Component italicHint(String text) {
        return Component.literal(text).withStyle(s -> s.withItalic(true).withColor(ChatFormatting.DARK_GRAY));
    }

    static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(color));
    }

    static Component labeledValue(String label, String value, ChatFormatting labelColor) {
        return Component.literal(label + ": ").withStyle(s -> s.withItalic(false).withColor(labelColor))
                .append(Component.literal(value).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
    }

    static ItemStack backButton() {
        return button(Items.BARRIER, "Back", ChatFormatting.DARK_RED);
    }

    static ItemStack cancelButton() {
        return button(redStainedGlassPane(), "Cancel", ChatFormatting.DARK_RED);
    }

    static ItemStack confirmButton(String name, Component... lore) {
        return button(limeStainedGlassPane(), name, ChatFormatting.GREEN, lore);
    }

    static void denyPermission(ServerPlayer player) {
        failure(player);
        player.sendSystemMessage(line("You don't have permission for that.", ChatFormatting.RED));
    }

    static void fillBackground(Container container) {
        ItemStack filler = new ItemStack(grayStainedGlassPane());
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) container.setItem(i, filler.copy());
        }
    }

    static Item grayStainedGlassPane() {
        Item item = grayPane;
        if (item == null) grayPane = item = itemById("gray_stained_glass_pane");
        return item;
    }

    static Item redStainedGlassPane() {
        Item item = redPane;
        if (item == null) redPane = item = itemById("red_stained_glass_pane");
        return item;
    }

    static Item limeStainedGlassPane() {
        Item item = limePane;
        if (item == null) limePane = item = itemById("lime_stained_glass_pane");
        return item;
    }

    private static Item itemById(String path) {
        String id = "minecraft:" + path;
        for (Item item : BuiltInRegistries.ITEM) {
            if (id.equals(String.valueOf(BuiltInRegistries.ITEM.getKey(item)))) return item;
        }
        return Items.AIR;
    }

    static void click(ServerPlayer player) {
        play(player, SoundEvents.UI_BUTTON_CLICK, 0.25F, 1.0F);
    }

    static void failure(ServerPlayer player) {
        SoundEvent sound = failureSound;
        if (sound == null) failureSound = sound = failureSoundEvent();
        play(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), 0.4F, 1.0F);
    }

    private static SoundEvent failureSoundEvent() {
        for (SoundEvent sound : BuiltInRegistries.SOUND_EVENT) {
            if ("minecraft:item.bundle.insert_fail".equals(String.valueOf(BuiltInRegistries.SOUND_EVENT.getKey(sound)))) {
                return sound;
            }
        }
        return SoundEvents.VILLAGER_NO;
    }

    private static void play(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

    static Slot lockedSlot(Container container, int index, int x, int y) {
        return new Slot(container, index, x, y) {
            @Override
            public boolean mayPickup(Player player) {
                return false;
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        };
    }

    static List<Slot> chestSlots(Container container, Inventory inv) {
        List<Slot> slots = new ArrayList<>(GRID_SIZE + 36);
        for (int i = 0; i < GRID_SIZE; i++) {
            slots.add(lockedSlot(container, i, GRID_LEFT + i % 9 * SLOT_SIZE, GRID_TOP + i / 9 * SLOT_SIZE));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slots.add(new Slot(inv, col + row * 9 + 9, GRID_LEFT + col * SLOT_SIZE, INVENTORY_TOP + row * SLOT_SIZE));
            }
        }
        for (int col = 0; col < 9; col++) {
            slots.add(new Slot(inv, col, GRID_LEFT + col * SLOT_SIZE, INVENTORY_TOP + 58));
        }
        return slots;
    }
}
