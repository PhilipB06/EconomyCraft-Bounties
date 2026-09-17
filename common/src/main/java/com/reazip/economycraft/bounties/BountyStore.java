package com.reazip.economycraft.bounties;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reazip.economycraft.bounties.Bounty.Contribution;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BountyStore {
    private static final int SCHEMA_VERSION = 1;
    private static final UUID LEGACY_CONTRIBUTOR = new UUID(0L, 0L);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    BountyStore(Path file) {
        this.file = file;
    }

    Map<UUID, Bounty> load() {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("Bounty data root at " + file + " is not an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("bounties")) {
                return loadCurrent(root);
            }
            LegacyLoad legacy = loadLegacyTotals(root);
            if (legacy.skipped() > 0) {
                throw new IllegalStateException("Bounty data at " + file + " has no 'bounties' key and "
                        + legacy.skipped() + " unreadable entry/entries alongside " + legacy.recognized()
                        + " legacy one(s); refusing to overwrite it. Fix or remove the file manually.");
            }
            Map<UUID, Bounty> loaded = legacy.bounties();
            if (legacy.recognized() > 0) {
                EconomyCraftBounties.LOGGER.info(
                        "[EconomyCraft Bounties] Migrated legacy aggregate bounty data at {}.", file);
            }
            save(loaded);
            return loaded;
        } catch (IllegalStateException refusal) {
            throw refusal;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read bounty data at " + file, e);
        }
    }

    void save(Map<UUID, Bounty> bounties) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        JsonArray entries = new JsonArray();

        List<Bounty> sorted = new ArrayList<>(bounties.values());
        sorted.sort(Comparator.comparing(bounty -> bounty.targetId().toString()));
        for (Bounty bounty : sorted) {
            JsonObject entry = new JsonObject();
            entry.addProperty("target_uuid", bounty.targetId().toString());
            entry.addProperty("target_name", bounty.targetName());

            JsonArray contributors = new JsonArray();
            bounty.contributions().values().stream()
                    .sorted(Comparator.comparing(contribution -> contribution.contributorId().toString()))
                    .forEach(contribution -> {
                        JsonObject value = new JsonObject();
                        value.addProperty("contributor_uuid", contribution.contributorId().toString());
                        value.addProperty("contributor_name", contribution.name());
                        value.addProperty("amount", contribution.amount());
                        contributors.add(value);
                    });
            entry.add("contributors", contributors);
            entries.add(entry);
        }
        root.add("bounties", entries);

        try {
            BountyFiles.writeAtomically(file, GSON.toJson(root));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save bounty data at " + file, e);
        }
    }

    private Map<UUID, Bounty> loadCurrent(JsonObject root) {
        JsonElement bountiesElement = root.get("bounties");
        if (!bountiesElement.isJsonArray()) {
            throw new IllegalStateException("Bounty data at " + file + " has a 'bounties' key that is not an array");
        }
        Map<UUID, Bounty> loaded = new LinkedHashMap<>();
        int invalid = 0;
        for (JsonElement element : bountiesElement.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            try {
                UUID target = UUID.fromString(object.get("target_uuid").getAsString());
                String storedName = stringOrNull(object, "target_name");
                Bounty bounty = new Bounty(target, storedName != null ? storedName : target.toString());
                JsonElement contributors = object.get("contributors");
                if (contributors != null && contributors.isJsonArray()) {
                    for (JsonElement contributorElement : contributors.getAsJsonArray()) {
                        if (!contributorElement.isJsonObject()) continue;
                        JsonObject contributor = contributorElement.getAsJsonObject();
                        UUID contributorId = UUID.fromString(contributor.get("contributor_uuid").getAsString());
                        long amount = wholeAmount(contributor.get("amount"));
                        if (amount <= 0L) continue;
                        String contributorName = stringOrNull(contributor, "contributor_name");
                        bounty.addContribution(contributorId,
                                contributorName != null ? contributorName : contributorId.toString(), amount);
                    }
                }
                if (bounty.contributions().isEmpty()) continue;
                Bounty existing = loaded.get(target);
                if (existing == null) {
                    bounty.total();
                    loaded.put(target, bounty);
                } else {
                    Math.addExact(existing.total(), bounty.total());
                    EconomyCraftBounties.LOGGER.warn(
                            "[EconomyCraft Bounties] Duplicate bounty entry for {} in {}; merging contributions.",
                            target, file);
                    existing.setTargetName(storedName);
                    for (Contribution contribution : bounty.contributions().values()) {
                        existing.addContribution(
                                contribution.contributorId(), contribution.name(), contribution.amount());
                    }
                }
            } catch (Exception invalidEntry) {
                invalid++;
                EconomyCraftBounties.LOGGER.warn(
                        "[EconomyCraft Bounties] Invalid bounty data entry: {}", element, invalidEntry);
            }
        }
        if (invalid > 0) {
            throw new IllegalStateException("Bounty data at " + file + " has " + invalid
                    + " invalid entry/entries; refusing to overwrite it. Fix or remove the file manually.");
        }
        return loaded;
    }

    private static long wholeAmount(JsonElement amountElement) {
        if (amountElement == null || !amountElement.isJsonPrimitive()
                || !amountElement.getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("amount is not numeric");
        }
        BigDecimal value;
        try {
            value = amountElement.getAsBigDecimal();
        } catch (NumberFormatException notANumber) {
            throw new IllegalStateException("amount is not a finite number: " + amountElement, notANumber);
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new IllegalStateException("amount is not a whole number: " + value.toPlainString());
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException outOfRange) {
            throw new IllegalStateException(
                    "amount is outside the supported range: " + value.toPlainString(), outOfRange);
        }
    }

    private LegacyLoad loadLegacyTotals(JsonObject root) {
        Map<UUID, Bounty> loaded = new LinkedHashMap<>();
        int recognized = 0;
        int skipped = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                UUID target = UUID.fromString(entry.getKey());
                long amount = entry.getValue().getAsLong();
                recognized++;
                if (amount <= 0L) continue;
                Bounty bounty = new Bounty(target, target.toString());
                bounty.addContribution(LEGACY_CONTRIBUTOR, "Legacy bounty", amount);
                loaded.put(target, bounty);
            } catch (Exception invalidEntry) {
                skipped++;
                EconomyCraftBounties.LOGGER.warn(
                        "[EconomyCraft Bounties] Skipping invalid legacy bounty entry {}.", entry.getKey(),
                        invalidEntry);
            }
        }
        return new LegacyLoad(loaded, recognized, skipped);
    }

    private static String stringOrNull(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private record LegacyLoad(Map<UUID, Bounty> bounties, int recognized, int skipped) {
    }
}
