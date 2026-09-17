package com.reazip.economycraft.bounties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Bounty {
    private final UUID targetId;
    private String targetName;
    private final Map<UUID, Contribution> contributions = new LinkedHashMap<>();

    Bounty(UUID targetId, String targetName) {
        this.targetId = targetId;
        this.targetName = targetName;
    }

    public UUID targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public Map<UUID, Contribution> contributions() {
        return Collections.unmodifiableMap(contributions);
    }

    public long total() {
        long total = 0L;
        for (Contribution contribution : contributions.values()) {
            total = Math.addExact(total, contribution.amount());
        }
        return total;
    }

    void setTargetName(String name) {
        if (name != null && !name.isBlank()) targetName = name;
    }

    void addContribution(UUID contributorId, String contributorName, long amount) {
        Contribution current = contributions.get(contributorId);
        long updated = Math.addExact(current == null ? 0L : current.amount(), amount);
        String name = contributorName;
        if ((name == null || name.isBlank()) && current != null) name = current.name();
        contributions.put(contributorId, new Contribution(contributorId, name, updated));
    }

    Bounty copy() {
        Bounty copy = new Bounty(targetId, targetName);
        copy.contributions.putAll(contributions);
        return copy;
    }

    public record Contribution(UUID contributorId, String name, long amount) {
    }
}
