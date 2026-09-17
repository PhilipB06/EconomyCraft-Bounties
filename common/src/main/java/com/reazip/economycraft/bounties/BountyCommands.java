package com.reazip.economycraft.bounties;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.reazip.economycraft.api.v1.EconomyCraftApi;
import com.reazip.economycraft.bounties.Bounty.Contribution;
import com.reazip.economycraft.bounties.BountyPermissions.Nodes;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class BountyCommands {
    private BountyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("bounty")
                .executes(BountyCommands::openBoard)
                .then(literal("add")
                        .requires(src -> BountyPermissions.checkCommand(src, Nodes.COMMAND_ADD))
                        .then(argument("target", GameProfileArgument.gameProfile())
                                .then(argument("amount", LongArgumentType.longArg(1L))
                                        .executes(BountyCommands::place))))
                .then(literal("info")
                        .requires(src -> BountyPermissions.checkCommand(src, Nodes.COMMAND_INFO))
                        .executes(BountyCommands::infoSelf)
                        .then(argument("target", GameProfileArgument.gameProfile())
                                .executes(BountyCommands::info)))
                .then(configNode()));
    }

    private static int openBoard(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can open the menu.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!BountyPermissions.checkCommand(source, Nodes.COMMAND_BOUNTY)) {
            source.sendFailure(Component.literal("You don't have permission for that.").withStyle(ChatFormatting.RED));
            return 0;
        }
        BountyUi.open(player);
        return 1;
    }

    private static int place(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (isSelectorArgument(context, "target")) {
            throw ERROR_SELECTOR_NOT_ALLOWED.create();
        }
        ServerPlayer contributor = context.getSource().getPlayerOrException();
        PlayerIdentity target = getSingleTarget(context);
        long amount = LongArgumentType.getLong(context, "amount");
        BountyService service = EconomyCraftBounties.service(context.getSource().getServer());
        BountyService.PlaceResult result = service.placeBounty(contributor, target, amount);

        if (result.successful()) {
            String formatted = EconomyCraftApi.get(context.getSource().getServer()).formatMoney(result.newTotal());
            context.getSource().sendSuccess(() -> Component.literal(
                    "Bounty placed. The total on " + target.name() + " is now " + formatted + ".")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        String failure = switch (result.status()) {
            case BELOW_MINIMUM -> "A bounty needs to be at least "
                    + EconomyCraftApi.get(context.getSource().getServer()).formatMoney(service.config().minimumBounty)
                    + ".";
            case SELF_NOT_ALLOWED -> "You cannot place a bounty on yourself.";
            case AMOUNT_TOO_LARGE -> tooLargeMessage(context, target, result.newTotal());
            case INSUFFICIENT_FUNDS -> "You do not have enough money.";
            case ECONOMY_REJECTED -> "EconomyCraft rejected the escrow transaction.";
            case PERSISTENCE_FAILED -> "The bounty could not be saved; your money was refunded.";
            case PERSISTENCE_AND_REFUND_FAILED ->
                    "The bounty could not be saved and your money could not be refunded; contact an administrator.";
            case SUCCESS -> throw new IllegalStateException("Unexpected successful result");
        };
        context.getSource().sendFailure(Component.literal(failure).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static String tooLargeMessage(CommandContext<CommandSourceStack> context, PlayerIdentity target,
                                          long currentTotal) {
        EconomyCraftApi api = EconomyCraftApi.get(context.getSource().getServer());
        long headroom = Math.max(0L, api.balances().getMaximumBalance() - currentTotal);
        if (headroom == 0L) {
            return "The bounty on " + target.name() + " is already at the maximum.";
        }
        return "That would push the bounty on " + target.name() + " past the maximum; you can add at most "
                + api.formatMoney(headroom) + " more.";
    }

    private static int infoSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return showInfo(context, new PlayerIdentity(player.getUUID(), player.getName().getString()));
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return showInfo(context, getSingleTarget(context));
    }

    private static int showInfo(CommandContext<CommandSourceStack> context, PlayerIdentity target) {
        boolean self = isSelf(context.getSource(), target);
        BountyService service = EconomyCraftBounties.service(context.getSource().getServer());
        Bounty bounty = service.bountyFor(target.id());
        if (bounty == null) {
            String who = self ? "you" : target.name();
            context.getSource().sendFailure(Component.literal(
                    "There is no active bounty on " + who + ".").withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyCraftApi api = EconomyCraftApi.get(context.getSource().getServer());
        String who = self ? "you" : bounty.targetName();
        context.getSource().sendSuccess(() -> Component.literal(
                "Bounty on " + who + ": " + api.formatMoney(bounty.total()))
                .withStyle(ChatFormatting.GOLD), false);
        bounty.contributions().values().stream()
                .sorted(Comparator.comparingLong(Contribution::amount).reversed())
                .forEach(contribution -> context.getSource().sendSuccess(() -> Component.literal(
                        "- " + contribution.name() + ": " + api.formatMoney(contribution.amount()))
                        .withStyle(ChatFormatting.YELLOW), false));
        return 1;
    }

    private static boolean isSelf(CommandSourceStack source, PlayerIdentity target) {
        ServerPlayer player = source.getPlayer();
        return player != null && player.getUUID().equals(target.id());
    }

    private record ConfigOption<T>(String key, Class<T> type, ArgumentType<T> argument,
                                   Function<BountyConfig, Object> reader,
                                   BiConsumer<BountyConfig, T> writer) {
    }

    private static final List<ConfigOption<?>> CONFIG_OPTIONS = List.<ConfigOption<?>>of(
            new ConfigOption<>("minimum_bounty", Long.class, LongArgumentType.longArg(1L),
                    config -> config.minimumBounty, (config, value) -> config.minimumBounty = value),
            new ConfigOption<>("allow_self_bounties", Boolean.class, BoolArgumentType.bool(),
                    config -> config.allowSelfBounties, (config, value) -> config.allowSelfBounties = value),
            new ConfigOption<>("announce_bounty_placement", Boolean.class, BoolArgumentType.bool(),
                    config -> config.announceBountyPlacement,
                    (config, value) -> config.announceBountyPlacement = value),
            new ConfigOption<>("announce_bounty_claim", Boolean.class, BoolArgumentType.bool(),
                    config -> config.announceBountyClaim, (config, value) -> config.announceBountyClaim = value),
            new ConfigOption<>("pvp_balance_loss_percentage", Double.class, DoubleArgumentType.doubleArg(0.0, 1.0),
                    config -> config.pvpBalanceLossPercentage,
                    (config, value) -> config.pvpBalanceLossPercentage = value));

    private static LiteralArgumentBuilder<CommandSourceStack> configNode() {
        LiteralArgumentBuilder<CommandSourceStack> node = literal("config")
                .requires(src -> BountyPermissions.checkAdmin(src, Nodes.ADMIN_CONFIG))
                .executes(BountyCommands::openSettings);
        for (ConfigOption<?> option : CONFIG_OPTIONS) node = node.then(configOptionNode(option));
        return node;
    }

    private static <T> LiteralArgumentBuilder<CommandSourceStack> configOptionNode(ConfigOption<T> option) {
        return literal(option.key())
                .then(argument("value", option.argument())
                        .executes(context -> configSet(context, option)));
    }

    private static int openSettings(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can open the admin menu.").withStyle(ChatFormatting.RED));
            return 0;
        }
        BountyAdminUi.open(player);
        return 1;
    }

    private static <T> int configSet(CommandContext<CommandSourceStack> context, ConfigOption<T> option) {
        T value = context.getArgument("value", option.type());
        BountyConfig config = configOf(context);
        try {
            config.update(() -> option.writer().accept(config, value));
        } catch (RuntimeException failure) {
            EconomyCraftBounties.LOGGER.error("[EconomyCraft Bounties] Failed to save a config change.", failure);
            context.getSource().sendFailure(Component.literal(
                    option.key() + " could not be saved; it was not changed.").withStyle(ChatFormatting.RED));
            return 0;
        }
        Object stored = option.reader().apply(config);
        context.getSource().sendSuccess(() -> Component.literal(
                option.key() + " is now " + stored + ".").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static BountyConfig configOf(CommandContext<CommandSourceStack> context) {
        return EconomyCraftBounties.service(context.getSource().getServer()).config();
    }

    private static PlayerIdentity getSingleTarget(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        Collection<?> profiles = GameProfileArgument.getGameProfiles(context, "target");
        if (profiles.size() != 1) {
            throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        }
        try {
            return PlayerIdentity.fromUnknown(profiles.iterator().next());
        } catch (IllegalArgumentException e) {
            throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        }
    }

    private static final SimpleCommandExceptionType ERROR_SELECTOR_NOT_ALLOWED = new SimpleCommandExceptionType(
            Component.literal("Bounty targets must be a player name, not a selector."));

    private static boolean isSelectorArgument(CommandContext<CommandSourceStack> context, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode() instanceof ArgumentCommandNode<?, ?> argumentNode
                    && argumentNode.getName().equals(name)) {
                return node.getRange().get(context.getInput()).startsWith("@");
            }
        }
        return false;
    }
}
