package com.reazip.economycraft.bounties;

import com.reazip.economycraft.api.v1.EconomyCraftApi;
import com.reazip.economycraft.bounties.BountyPermissions.Nodes;
import com.reazip.economycraft.bounties.MenuSupport.ClickKind;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongFunction;

public final class BountyAdminUi {
    private static final int[] MONEY_STEPS = {1000, 100, 10, 1};
    private static final int[] PERCENT_STEPS = {25, 10, 5, 1};
    private static final String[] SHORT_MONEY_SUFFIXES = {"", "k", "M", "B", "T"};

    private BountyAdminUi() {
    }

    public static void open(ServerPlayer player) {
        if (!BountyPermissions.checkAdmin(player.createCommandSourceStack(), Nodes.ADMIN_CONFIG)) {
            MenuSupport.denyPermission(player);
            return;
        }
        MinecraftServer server = player.level().getServer();
        BountyConfig config = EconomyCraftBounties.service(server).config();
        openMenu(player, "Bounty Settings", (id, inv, p) -> new SettingsMenu(id, inv, player, server, config));
    }

    private static boolean tryUpdate(ServerPlayer player, BountyConfig config, Runnable mutation) {
        try {
            config.update(mutation);
            return true;
        } catch (RuntimeException failure) {
            EconomyCraftBounties.LOGGER.error("[EconomyCraft Bounties] Failed to save a config change.", failure);
            MenuSupport.failure(player);
            player.sendSystemMessage(MenuSupport.line("The setting could not be saved; it was not changed.",
                    ChatFormatting.RED));
            return false;
        }
    }

    private static void openMenu(ServerPlayer player, String title, MenuConstructor constructor) {
        player.openMenu(new SimpleMenuProvider(constructor, Component.literal(title)));
    }

    private enum Setting {
        MINIMUM_BOUNTY("Minimum Bounty", "Smallest amount a single /bounty add can place."),
        ALLOW_SELF_BOUNTIES("Self Bounties", "Whether a player can place a bounty on themselves."),
        ANNOUNCE_PLACEMENT("Announce Placement", "Broadcast a message when a bounty is placed."),
        ANNOUNCE_CLAIM("Announce Claim", "Broadcast a message when a bounty is claimed."),
        PVP_LOSS_PERCENTAGE("PvP Balance Loss", "Share of a balance the killer takes on any PvP death.");

        final String label;
        final String description;

        Setting(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    private static final class SettingsMenu extends CompatMenu {
        private static final int HEADER_SLOT = 4;
        private static final int BACK_SLOT = 18;
        private static final int[] SETTING_SLOTS = {10, 11, 12, 13, 14};

        private final ServerPlayer viewer;
        private final MinecraftServer server;
        private final BountyConfig config;
        private final SimpleContainer container = new SimpleContainer(MenuSupport.GRID_SIZE);
        private final Setting[] settings = Setting.values();

        SettingsMenu(int id, Inventory inv, ServerPlayer viewer, MinecraftServer server, BountyConfig config) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.server = server;
            this.config = config;

            for (Slot slot : MenuSupport.chestSlots(container, inv)) addSlot(slot);
            render();
        }

        private void render() {
            container.clearContent();
            container.setItem(HEADER_SLOT, MenuSupport.button(Items.BOOK, "Bounty Settings", ChatFormatting.YELLOW,
                    MenuSupport.hint("Click a setting to change it."),
                    MenuSupport.hint("Changes save straight to config.json.")));
            for (int i = 0; i < settings.length; i++) {
                container.setItem(SETTING_SLOTS[i], buildItem(settings[i]));
            }
            container.setItem(BACK_SLOT, MenuSupport.backButton());
            MenuSupport.fillBackground(container);
        }

        private ItemStack buildItem(Setting setting) {
            return switch (setting) {
                case MINIMUM_BOUNTY -> valueItem(setting, Items.GOLD_INGOT, money(config.minimumBounty));
                case ALLOW_SELF_BOUNTIES -> toggleItem(setting, config.allowSelfBounties);
                case ANNOUNCE_PLACEMENT -> toggleItem(setting, config.announceBountyPlacement);
                case ANNOUNCE_CLAIM -> toggleItem(setting, config.announceBountyClaim);
                case PVP_LOSS_PERCENTAGE -> valueItem(setting, Items.IRON_SWORD, percent(config.pvpBalanceLossPercentage));
            };
        }

        private ItemStack valueItem(Setting setting, Item icon, String current) {
            return MenuSupport.button(icon, setting.label, ChatFormatting.AQUA,
                    MenuSupport.hint(setting.description),
                    MenuSupport.labeledValue("Now", current, ChatFormatting.GOLD),
                    MenuSupport.labeledValue("Click", "Change it", ChatFormatting.AQUA));
        }

        private ItemStack toggleItem(Setting setting, boolean enabled) {
            return MenuSupport.button(
                    enabled ? MenuSupport.limeStainedGlassPane() : MenuSupport.redStainedGlassPane(),
                    setting.label,
                    enabled ? ChatFormatting.GREEN : ChatFormatting.RED,
                    MenuSupport.hint(setting.description),
                    MenuSupport.labeledValue("Now", enabled ? "On" : "Off", ChatFormatting.GOLD),
                    MenuSupport.labeledValue("Click", enabled ? "Turn off" : "Turn on", ChatFormatting.AQUA));
        }

        private String money(long amount) {
            return EconomyCraftApi.get(server).formatMoney(amount);
        }

        private static String percent(double fraction) {
            return Math.round(fraction * 100) + "%";
        }

        @Override
        protected boolean onClick(int slot, ClickKind kind) {
            if (slot < 0 || slot >= MenuSupport.GRID_SIZE) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;

            if (slot == BACK_SLOT) {
                MenuSupport.click(viewer);
                BountyUi.open(viewer);
                return true;
            }

            int index = indexOfSettingSlot(slot);
            if (index < 0) return true;

            Setting setting = settings[index];
            MenuSupport.click(viewer);
            switch (setting) {
                case MINIMUM_BOUNTY -> NumberMenu.open(viewer, setting.label, new ItemStack(Items.GOLD_INGOT),
                        setting.label, config.minimumBounty, 1,
                        EconomyCraftApi.get(server).balances().getMaximumBalance(), MONEY_STEPS, this::money,
                        (p, next) -> {
                            if (tryUpdate(p, config, () -> config.minimumBounty = next)) MenuSupport.click(p);
                            open(p);
                        },
                        BountyAdminUi::open);
                case ALLOW_SELF_BOUNTIES -> toggle(() -> config.allowSelfBounties = !config.allowSelfBounties);
                case ANNOUNCE_PLACEMENT -> toggle(() -> config.announceBountyPlacement = !config.announceBountyPlacement);
                case ANNOUNCE_CLAIM -> toggle(() -> config.announceBountyClaim = !config.announceBountyClaim);
                case PVP_LOSS_PERCENTAGE -> NumberMenu.open(viewer, setting.label, new ItemStack(Items.IRON_SWORD),
                        setting.label, Math.round(config.pvpBalanceLossPercentage * 100), 0, 100,
                        PERCENT_STEPS, v -> v + "%",
                        (p, next) -> {
                            if (tryUpdate(p, config, () -> config.pvpBalanceLossPercentage = next / 100.0)) {
                                MenuSupport.click(p);
                            }
                            open(p);
                        },
                        BountyAdminUi::open);
            }
            return true;
        }

        private void toggle(Runnable flip) {
            tryUpdate(viewer, config, flip);
            render();
        }

        private static int indexOfSettingSlot(int slot) {
            for (int i = 0; i < SETTING_SLOTS.length; i++) {
                if (SETTING_SLOTS[i] == slot) return i;
            }
            return -1;
        }
    }

    private static final class NumberMenu extends CompatMenu {
        private static final int VALUE_SLOT = 4;
        private static final int TYPE_SLOT = 13;
        private static final int CANCEL_SLOT = 18;
        private static final int CONFIRM_SLOT = 26;

        private final ServerPlayer viewer;
        private final ItemStack subject;
        private final String label;
        private final long min;
        private final long max;
        private final int[] steps;
        private final LongFunction<String> format;
        private final BiConsumer<ServerPlayer, Long> onConfirm;
        private final Consumer<ServerPlayer> onCancel;
        private final SimpleContainer container = new SimpleContainer(MenuSupport.GRID_SIZE);
        private long value;

        static void open(ServerPlayer player, String title, ItemStack subject, String label, long initial, long min,
                         long max, int[] steps, LongFunction<String> format,
                         BiConsumer<ServerPlayer, Long> onConfirm, Consumer<ServerPlayer> onCancel) {
            openMenu(player, title, (id, inv, p) ->
                    new NumberMenu(id, inv, player, subject, label, initial, min, max, steps, format, onConfirm, onCancel));
        }

        private NumberMenu(int id, Inventory inv, ServerPlayer viewer, ItemStack subject, String label, long initial,
                           long min, long max, int[] steps, LongFunction<String> format,
                           BiConsumer<ServerPlayer, Long> onConfirm, Consumer<ServerPlayer> onCancel) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.subject = subject == null || subject.isEmpty() ? new ItemStack(Items.PAPER) : subject.copy();
            this.label = label;
            this.min = min;
            this.max = max;
            this.steps = steps;
            this.format = format;
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
            this.value = Math.clamp(initial, min, max);

            for (Slot slot : MenuSupport.chestSlots(container, inv)) addSlot(slot);
            render();
        }

        private void render() {
            container.clearContent();

            for (int i = 0; i < steps.length && i < 4; i++) {
                container.setItem(i, MenuSupport.button(MenuSupport.redStainedGlassPane(),
                        "-" + steps[i], ChatFormatting.RED, MenuSupport.italicHint("Shift-click for x10")));
                container.setItem(8 - i, MenuSupport.button(MenuSupport.limeStainedGlassPane(),
                        "+" + steps[i], ChatFormatting.GREEN, MenuSupport.italicHint("Shift-click for x10")));
            }

            ItemStack display = subject.copy();
            display.setCount(1);
            display.set(DataComponents.CUSTOM_NAME, Component.literal(format.apply(value))
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.YELLOW)));
            display.set(DataComponents.LORE, new ItemLore(List.of(
                    MenuSupport.labeledValue(label, format.apply(value), ChatFormatting.GOLD),
                    MenuSupport.hint("Allowed: " + format.apply(min) + " - " + format.apply(max)))));
            container.setItem(VALUE_SLOT, display);

            container.setItem(TYPE_SLOT, MenuSupport.button(Items.NAME_TAG, "Type a value",
                    ChatFormatting.AQUA, MenuSupport.hint("Enter the number by hand")));

            container.setItem(CANCEL_SLOT, MenuSupport.cancelButton());
            container.setItem(CONFIRM_SLOT, MenuSupport.confirmButton("Confirm",
                    MenuSupport.labeledValue(label, format.apply(value), ChatFormatting.GOLD)));

            MenuSupport.fillBackground(container);
        }

        private void apply(long delta) {
            long next;
            try {
                next = Math.addExact(value, delta);
            } catch (ArithmeticException overflow) {
                next = delta > 0 ? max : min;
            }
            value = Math.clamp(next, min, max);
            render();
        }

        @Override
        protected boolean onClick(int slot, ClickKind kind) {
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) {
                return slot >= 0 && slot < MenuSupport.GRID_SIZE;
            }
            if (slot < 0 || slot >= MenuSupport.GRID_SIZE) return false;

            long multiplier = kind == ClickKind.QUICK_MOVE ? 10 : 1;

            for (int i = 0; i < steps.length && i < 4; i++) {
                if (slot == i) {
                    MenuSupport.click(viewer);
                    apply(-steps[i] * multiplier);
                    return true;
                }
                if (slot == 8 - i) {
                    MenuSupport.click(viewer);
                    apply(steps[i] * multiplier);
                    return true;
                }
            }

            if (slot == TYPE_SLOT) {
                MenuSupport.click(viewer);
                TextInputMenu.open(viewer, "Enter a value", String.valueOf(value), Items.NAME_TAG,
                        "Use: ", "Type a number", (p, text) -> {
                            Long parsed = parse(text);
                            if (parsed == null) {
                                p.sendSystemMessage(MenuSupport.line("\"" + text + "\" is not a number.", ChatFormatting.RED));
                            }
                            long next = parsed == null ? value : Math.clamp(parsed, min, max);
                            open(p, "Set " + label, subject, label, next, min, max, steps, format, onConfirm, onCancel);
                        });
                return true;
            }

            if (slot == CANCEL_SLOT) {
                MenuSupport.click(viewer);
                viewer.closeContainer();
                if (onCancel != null) onCancel.accept(viewer);
                return true;
            }

            if (slot == CONFIRM_SLOT) {
                viewer.closeContainer();
                onConfirm.accept(viewer, value);
                return true;
            }

            return true;
        }

        private static Long parse(String text) {
            String trimmed = text.trim();
            Long shorthand = parseMoneyShort(trimmed);
            if (shorthand != null) return shorthand;

            String cleaned = trimmed.replaceAll("[^0-9.-]", "");
            if (cleaned.isEmpty() || cleaned.equals("-")) return null;
            try {
                return Long.parseLong(cleaned);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static Long parseMoneyShort(String input) {
            if (input == null) return null;
            String s = input.trim();
            if (s.isEmpty()) return null;

            for (int magnitude = SHORT_MONEY_SUFFIXES.length - 1; magnitude >= 1; magnitude--) {
                String suffix = SHORT_MONEY_SUFFIXES[magnitude];
                if (s.length() > suffix.length() && s.regionMatches(true, s.length() - suffix.length(), suffix, 0, suffix.length())) {
                    double parsed;
                    try {
                        parsed = Double.parseDouble(s.substring(0, s.length() - suffix.length()));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (!Double.isFinite(parsed) || parsed < 0) return null;
                    double scaled = parsed * Math.pow(1000, magnitude);
                    if (scaled > Long.MAX_VALUE) return null;
                    return Math.round(scaled);
                }
            }

            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static final class TextInputMenu extends AnvilMenu {
        private final Item icon;
        private final String confirmPrefix;
        private final String placeholder;
        private final BiConsumer<ServerPlayer, String> onConfirm;
        private String text;

        static void open(ServerPlayer player, String title, String initial, Item icon,
                         String confirmPrefix, String placeholder, BiConsumer<ServerPlayer, String> onConfirm) {
            openMenu(player, title, (id, inv, p) ->
                    new TextInputMenu(id, inv, initial, icon, confirmPrefix, placeholder, onConfirm));
        }

        private TextInputMenu(int id, Inventory inv, String initial, Item icon, String confirmPrefix, String placeholder,
                              BiConsumer<ServerPlayer, String> onConfirm) {
            super(id, inv, ContainerLevelAccess.NULL);
            this.icon = icon;
            this.confirmPrefix = confirmPrefix;
            this.placeholder = placeholder;
            this.onConfirm = onConfirm;
            this.text = initial == null ? "" : initial;

            ItemStack input = new ItemStack(Items.PAPER);
            input.set(DataComponents.CUSTOM_NAME, Component.literal(this.text));
            this.inputSlots.setItem(INPUT_SLOT, input);
            lockInputSlot(INPUT_SLOT);
            lockInputSlot(ADDITIONAL_SLOT);
            renderResult();
        }

        private void lockInputSlot(int index) {
            Slot original = this.slots.get(index);
            this.slots.set(index, MenuSupport.lockedSlot(this.inputSlots, index, original.x, original.y));
        }

        @Override
        public boolean setItemName(String name) {
            this.text = name;
            renderResult();
            return true;
        }

        private void renderResult() {
            String value = text == null ? "" : text;
            ItemStack result = new ItemStack(icon);
            Component name = value.isBlank()
                    ? Component.literal(placeholder).withStyle(s -> s.withItalic(false))
                    : Component.literal(confirmPrefix + value)
                            .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GREEN));
            result.set(DataComponents.CUSTOM_NAME, name);
            result.set(DataComponents.LORE, new ItemLore(List.of(
                    MenuSupport.hint(value.isBlank() ? "Type in the field above" : "Click here to confirm"))));
            this.resultSlots.setItem(0, result);
        }

        @Override
        protected boolean mayPickup(Player player, boolean hasItem) {
            return hasItem && text != null && !text.isBlank();
        }

        @Override
        protected void onTake(Player player, ItemStack stack) {
            stack.setCount(0);
            String value = text;
            this.setCarried(ItemStack.EMPTY);
            ServerPlayer serverPlayer = (ServerPlayer) player;
            serverPlayer.closeContainer();
            serverPlayer.connection.send(new ClientboundSetExperiencePacket(
                    serverPlayer.experienceProgress, serverPlayer.totalExperience, serverPlayer.experienceLevel));
            if (value != null && !value.isBlank()) {
                MenuSupport.click(serverPlayer);
                onConfirm.accept(serverPlayer, value.trim());
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }
}
