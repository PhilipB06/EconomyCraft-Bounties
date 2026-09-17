package com.reazip.economycraft.bounties;

import com.reazip.economycraft.api.v1.BalanceApi;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.BalanceMutationStatus;
import com.reazip.economycraft.api.v1.EconomyCraftApi;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.api.v1.PaymentResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BountyService {
    private static final MutationSource BOUNTY_ESCROW =
            MutationSource.of("economycraft_bounties:bounty/escrow");
    private static final MutationSource BOUNTY_PAYOUT =
            MutationSource.of("economycraft_bounties:bounty/payout");
    private static final MutationSource PVP_REWARD =
            MutationSource.of("economycraft_bounties:pvp_reward");
    private static final int FLUSH_RETRY_TICKS = 100;

    private final MinecraftServer server;
    private final BountyConfig config;
    private final BountyStore store;
    private final Map<UUID, Bounty> bounties;
    private boolean dirty;
    private int nextFlushAttemptTick;

    BountyService(MinecraftServer server, BountyConfig config, BountyStore store,
                  Map<UUID, Bounty> bounties) {
        this.server = server;
        this.config = config;
        this.store = store;
        this.bounties = new LinkedHashMap<>(bounties);
    }

    public int size() {
        requireServerThread();
        return bounties.size();
    }

    public BountyConfig config() {
        requireServerThread();
        return config;
    }

    public Bounty bountyFor(UUID targetId) {
        requireServerThread();
        Bounty bounty = bounties.get(targetId);
        return bounty == null ? null : bounty.copy();
    }

    public List<Bounty> activeBounties() {
        requireServerThread();
        List<Bounty> snapshot = new ArrayList<>();
        for (Bounty bounty : bounties.values()) snapshot.add(bounty.copy());
        snapshot.sort(Comparator.comparingLong(Bounty::total).reversed()
                .thenComparing(Bounty::targetName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Bounty::targetId));
        return List.copyOf(snapshot);
    }

    public PlaceResult placeBounty(ServerPlayer contributor, PlayerIdentity target, long amount) {
        requireServerThread();
        if (amount < config.minimumBounty) return new PlaceResult(PlaceStatus.BELOW_MINIMUM, 0L);
        if (!config.allowSelfBounties && contributor.getUUID().equals(target.id())) {
            return new PlaceResult(PlaceStatus.SELF_NOT_ALLOWED, 0L);
        }

        BalanceApi balances = EconomyCraftApi.get(server).balances();
        long maximum = balances.getMaximumBalance();
        Bounty existing = bounties.get(target.id());
        long currentTotal = existing == null ? 0L : existing.total();
        long newTotal;
        try {
            newTotal = Math.addExact(currentTotal, amount);
        } catch (ArithmeticException overflow) {
            return new PlaceResult(PlaceStatus.AMOUNT_TOO_LARGE, currentTotal);
        }
        if (newTotal > maximum) return new PlaceResult(PlaceStatus.AMOUNT_TOO_LARGE, currentTotal);

        BalanceMutationResult debit = balances.removeMoney(contributor.getUUID(), amount, BOUNTY_ESCROW);
        if (!debit.successful()) {
            PlaceStatus status = debit.status() == BalanceMutationStatus.INSUFFICIENT_FUNDS
                    ? PlaceStatus.INSUFFICIENT_FUNDS
                    : PlaceStatus.ECONOMY_REJECTED;
            return new PlaceResult(status, currentTotal);
        }

        Bounty before = existing == null ? null : existing.copy();
        Bounty updated = existing == null ? new Bounty(target.id(), target.name()) : existing;
        updated.setTargetName(target.name());
        updated.addContribution(contributor.getUUID(), contributor.getName().getString(), amount);
        bounties.put(target.id(), updated);

        try {
            store.save(bounties);
            dirty = false;
        } catch (RuntimeException persistenceFailure) {
            if (before == null) bounties.remove(target.id());
            else bounties.put(target.id(), before);

            BalanceMutationResult refund = balances.addMoney(contributor.getUUID(), amount, BOUNTY_ESCROW);
            if (!refund.successful()) {
                EconomyCraftBounties.LOGGER.error(
                        "[EconomyCraft Bounties] Failed to refund {} after bounty persistence failed for {}.",
                        amount, contributor.getUUID());
            }
            EconomyCraftBounties.LOGGER.error(
                    "[EconomyCraft Bounties] Failed to persist bounty on {}.", target.id(), persistenceFailure);
            PlaceStatus status = refund.successful()
                    ? PlaceStatus.PERSISTENCE_FAILED
                    : PlaceStatus.PERSISTENCE_AND_REFUND_FAILED;
            return new PlaceResult(status, currentTotal);
        }

        if (config.announceBountyPlacement) {
            EconomyCraftApi api = EconomyCraftApi.get(server);
            String who = contributor.getUUID().equals(target.id()) ? "themselves" : target.name();
            broadcast(contributor.getName().getString() + " placed " + api.formatMoney(amount) + " on " + who
                    + ". Total bounty: " + api.formatMoney(newTotal) + ".", ChatFormatting.YELLOW);
        }
        return new PlaceResult(PlaceStatus.SUCCESS, newTotal);
    }

    public void processPlayerKill(ServerPlayer victim, ServerPlayer killer) {
        requireServerThread();
        payBounty(victim, killer);
        processPvpBalanceLoss(victim, killer);
    }

    public void save() {
        requireServerThread();
        try {
            store.save(bounties);
            dirty = false;
        } catch (RuntimeException e) {
            EconomyCraftBounties.LOGGER.error("[EconomyCraft Bounties] Failed to save bounty data.", e);
        }
    }

    public void flushPendingSave() {
        requireServerThread();
        if (!dirty || server.getTickCount() < nextFlushAttemptTick) return;
        save();
        if (dirty) nextFlushAttemptTick = server.getTickCount() + FLUSH_RETRY_TICKS;
    }

    private void payBounty(ServerPlayer victim, ServerPlayer killer) {
        Bounty bounty = bounties.get(victim.getUUID());
        if (bounty == null) return;

        long reward = bounty.total();
        EconomyCraftApi api = EconomyCraftApi.get(server);

        bounties.remove(victim.getUUID());
        try {
            store.save(bounties);
            dirty = false;
        } catch (RuntimeException persistenceFailure) {
            bounties.put(victim.getUUID(), bounty);
            EconomyCraftBounties.LOGGER.error(
                    "[EconomyCraft Bounties] Could not persist the claim of {} bounty on {} by {}; "
                            + "bounty remains active and was not paid.",
                    reward, victim.getUUID(), killer.getUUID(), persistenceFailure);
            killer.sendSystemMessage(Component.literal(
                    "The bounty on " + victim.getName().getString()
                            + " could not be recorded as claimed and remains active.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        BalanceMutationResult payout = api.balances().addMoney(killer.getUUID(), reward, BOUNTY_PAYOUT);
        if (!payout.successful()) {
            bounties.put(victim.getUUID(), bounty);
            dirty = true;
            save();
            EconomyCraftBounties.LOGGER.warn(
                    "[EconomyCraft Bounties] Could not pay {} bounty on {} to {} ({}); bounty remains active.",
                    reward, victim.getUUID(), killer.getUUID(), payout.status());
            killer.sendSystemMessage(Component.literal(
                    "The bounty on " + victim.getName().getString() + " could not be paid (" + payout.status()
                            + ") and remains active.").withStyle(ChatFormatting.RED));
            return;
        }

        if (config.announceBountyClaim) {
            broadcast(killer.getName().getString() + " claimed " + api.formatMoney(reward)
                    + " on " + victim.getName().getString() + ".", ChatFormatting.GREEN);
        }
    }

    private void processPvpBalanceLoss(ServerPlayer victim, ServerPlayer killer) {
        double percentage = config.pvpBalanceLossPercentage;
        if (percentage <= 0.0) return;

        EconomyCraftApi api = EconomyCraftApi.get(server);
        BalanceApi balances = api.balances();
        long victimBalance = balances.getBalance(victim.getUUID());
        if (victimBalance <= 0L) return;

        long loss = Math.min(
                BigDecimal.valueOf(victimBalance).multiply(BigDecimal.valueOf(percentage))
                        .setScale(0, RoundingMode.FLOOR).longValue(),
                victimBalance);
        if (loss <= 0L) return;

        PaymentResult result = balances.pay(victim.getUUID(), killer.getUUID(), loss, PVP_REWARD);
        if (!result.successful()) {
            EconomyCraftBounties.LOGGER.warn(
                    "[EconomyCraft Bounties] Could not transfer {} of {}'s balance to {} ({}).",
                    loss, victim.getUUID(), killer.getUUID(), result);
            killer.sendSystemMessage(Component.literal("The PvP payout from " + victim.getName().getString()
                    + " could not be transferred.").withStyle(ChatFormatting.RED));
            return;
        }

        victim.sendSystemMessage(Component.literal("You lost " + api.formatMoney(loss)
                + " for being killed by " + killer.getName().getString() + ".").withStyle(ChatFormatting.RED));
        killer.sendSystemMessage(Component.literal("You received " + api.formatMoney(loss)
                + " for killing " + victim.getName().getString() + ".").withStyle(ChatFormatting.GREEN));
    }

    private void broadcast(String message, ChatFormatting color) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("[Bounty] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(color)), false);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("EconomyCraft Bounties must run on the server thread");
        }
    }

    public enum PlaceStatus {
        SUCCESS,
        BELOW_MINIMUM,
        SELF_NOT_ALLOWED,
        AMOUNT_TOO_LARGE,
        INSUFFICIENT_FUNDS,
        ECONOMY_REJECTED,
        PERSISTENCE_FAILED,
        PERSISTENCE_AND_REFUND_FAILED
    }

    public record PlaceResult(PlaceStatus status, long newTotal) {
        public boolean successful() {
            return status == PlaceStatus.SUCCESS;
        }
    }
}
