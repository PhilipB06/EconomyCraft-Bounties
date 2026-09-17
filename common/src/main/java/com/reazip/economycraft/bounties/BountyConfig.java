package com.reazip.economycraft.bounties;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class BountyConfig {
    private static final String DEFAULT_RESOURCE = "/assets/economycraft_bounties/config.json";
    private static final String OLD_PVP_KEY = "pvp_balance_loss_percentage";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("pvp_balance_loss_percentage")
    public double pvpBalanceLossPercentage;
    @SerializedName("minimum_bounty")
    public long minimumBounty = 1L;
    @SerializedName("allow_self_bounties")
    public boolean allowSelfBounties = true;
    @SerializedName("announce_bounty_placement")
    public boolean announceBountyPlacement = true;
    @SerializedName("announce_bounty_claim")
    public boolean announceBountyClaim = true;

    private transient Path file;
    private transient JsonObject unknownKeys;

    private BountyConfig() {
    }

    public static BountyConfig load(MinecraftServer server) {
        Path file = BountyFiles.configFile(server);
        boolean firstRun = !Files.isRegularFile(file);
        JsonObject root = readUserConfig(file);
        addMissing(root, readDefaults());

        BountyConfig config;
        try {
            config = GSON.fromJson(root, BountyConfig.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("EconomyCraft Bounties config at " + file
                    + " has an invalid value; fix or remove the file manually.", e);
        }
        config.file = file;
        config.unknownKeys = collectUnknownKeys(root, config);
        config.validate();
        config.save();

        if (firstRun) {
            config.migrateEconomyCraftPvpLoss(server);
        }
        return config;
    }

    private void save() {
        JsonObject out = GSON.toJsonTree(this).getAsJsonObject();
        if (unknownKeys != null) {
            for (Map.Entry<String, JsonElement> entry : unknownKeys.entrySet()) {
                if (!out.has(entry.getKey())) out.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        try {
            BountyFiles.writeAtomically(file, GSON.toJson(out));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save EconomyCraft Bounties config at " + file, e);
        }
    }

    public void update(Runnable mutation) {
        BountyConfig previous = new BountyConfig();
        previous.copyValuesFrom(this);
        mutation.run();
        validate();
        try {
            save();
        } catch (RuntimeException failure) {
            copyValuesFrom(previous);
            throw failure;
        }
    }

    private void copyValuesFrom(BountyConfig source) {
        pvpBalanceLossPercentage = source.pvpBalanceLossPercentage;
        minimumBounty = source.minimumBounty;
        allowSelfBounties = source.allowSelfBounties;
        announceBountyPlacement = source.announceBountyPlacement;
        announceBountyClaim = source.announceBountyClaim;
    }

    private void validate() {
        pvpBalanceLossPercentage = normalizeLossFraction(pvpBalanceLossPercentage, "the bounty config");
        if (minimumBounty < 1L) minimumBounty = 1L;
    }

    private static double normalizeLossFraction(double value, String source) {
        if (Double.isFinite(value)) {
            if (value >= 0.0 && value <= 1.0) return value;
            if (value > 1.0 && value <= 100.0) {
                double rescaled = value / 100.0;
                EconomyCraftBounties.LOGGER.warn(
                        "[EconomyCraft Bounties] pvp_balance_loss_percentage {} from {} is outside the 0-1 range; "
                                + "reading it as a 0-100 percentage ({}).", value, source, rescaled);
                return rescaled;
            }
        }
        EconomyCraftBounties.LOGGER.warn(
                "[EconomyCraft Bounties] Ignoring unusable pvp_balance_loss_percentage {} from {}; PvP balance "
                        + "loss is disabled.", value, source);
        return 0.0;
    }

    private void migrateEconomyCraftPvpLoss(MinecraftServer server) {
        Path economyCraftConfig = BountyFiles.economyCraftConfigFile(server);
        try {
            if (!Files.isRegularFile(economyCraftConfig)) {
                EconomyCraftBounties.LOGGER.info(
                        "[EconomyCraft Bounties] No EconomyCraft config found at {}; nothing to migrate.",
                        economyCraftConfig);
                return;
            }

            JsonElement parsed = JsonParser.parseString(Files.readString(economyCraftConfig, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("EconomyCraft config root is not an object");
            JsonObject root = parsed.getAsJsonObject();

            JsonElement oldValue = root.get(OLD_PVP_KEY);
            if (oldValue == null || !oldValue.isJsonPrimitive() || !oldValue.getAsJsonPrimitive().isNumber()) {
                EconomyCraftBounties.LOGGER.info(
                        "[EconomyCraft Bounties] EconomyCraft config at {} has no legacy '{}' value; nothing to migrate.",
                        economyCraftConfig, OLD_PVP_KEY);
                return;
            }

            double oldPercentage = oldValue.getAsDouble();
            double migrated = normalizeLossFraction(oldPercentage, economyCraftConfig.toString());
            pvpBalanceLossPercentage = migrated;
            save();

            root.remove(OLD_PVP_KEY);
            BountyFiles.writeAtomically(economyCraftConfig, GSON.toJson(root));
            EconomyCraftBounties.LOGGER.info(
                    "[EconomyCraft Bounties] Copied EconomyCraft's PvP loss percentage ({} -> {}) from {} "
                            + "and removed '{}' from that file.",
                    oldPercentage, migrated, economyCraftConfig, OLD_PVP_KEY);
        } catch (Exception e) {
            EconomyCraftBounties.LOGGER.warn(
                    "[EconomyCraft Bounties] Could not migrate the legacy PvP loss percentage from {}; "
                            + "set 'pvp_balance_loss_percentage' in this addon's config by hand if you need it.",
                    economyCraftConfig, e);
        }
    }

    private static JsonObject readUserConfig(Path file) {
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("Config root is not an object");
            return parsed.getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read EconomyCraft Bounties config at " + file, e);
        }
    }

    private static JsonObject readDefaults() {
        try (InputStream input = BountyConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing bundled config " + DEFAULT_RESOURCE);
            JsonElement parsed = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("Bundled config root is not an object");
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled config " + DEFAULT_RESOURCE, e);
        }
    }

    private static void addMissing(JsonObject target, JsonObject defaults) {
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            if (!target.has(entry.getKey())) target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    private static JsonObject collectUnknownKeys(JsonObject root, BountyConfig config) {
        JsonObject known = GSON.toJsonTree(config).getAsJsonObject();
        JsonObject unknown = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!known.has(entry.getKey())) unknown.add(entry.getKey(), entry.getValue().deepCopy());
        }
        if (unknown.size() > 0) {
            EconomyCraftBounties.LOGGER.info(
                    "[EconomyCraft Bounties] Preserving {} unrecognized config key(s): {}.",
                    unknown.size(), unknown.keySet());
        }
        return unknown;
    }
}
