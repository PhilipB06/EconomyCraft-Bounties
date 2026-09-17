package com.reazip.economycraft.bounties;

import net.minecraft.commands.CommandSourceStack;

public final class BountyPermissions {
    private BountyPermissions() {
    }

    public static final class Nodes {
        private Nodes() {
        }

        public static final String[] ADMIN_ALL = {
                "economycraft_bounties.admin", "economycraft_bounties.admin.config"
        };
        public static final String ADMIN = ADMIN_ALL[0];
        public static final String ADMIN_CONFIG = ADMIN_ALL[1];

        public static final String[] COMMAND_ALL = {
                "economycraft_bounties.command.bounty", "economycraft_bounties.command.add",
                "economycraft_bounties.command.info"
        };
        public static final String COMMAND_BOUNTY = COMMAND_ALL[0];
        public static final String COMMAND_ADD = COMMAND_ALL[1];
        public static final String COMMAND_INFO = COMMAND_ALL[2];
    }

    public interface Backend {
        boolean check(CommandSourceStack source, String node, boolean fallback);
    }

    private static volatile Backend backend = (source, node, fallback) -> fallback;
    private static volatile boolean configured = false;

    public static void setBackend(Backend newBackend) {
        backend = newBackend;
        configured = true;
    }

    private static boolean check(CommandSourceStack source, String node, boolean fallback) {
        return backend.check(source, node, fallback);
    }

    public static boolean checkAdmin(CommandSourceStack source, String node) {
        if (!configured) return PermissionCompat.isOperator(source);
        Boolean specific = explicit(source, node);
        if (specific != null) return specific;
        Boolean blanket = explicit(source, Nodes.ADMIN);
        if (blanket != null) return blanket;
        return PermissionCompat.isOperator(source);
    }

    public static boolean hasAnyAdmin(CommandSourceStack source) {
        for (String node : Nodes.ADMIN_ALL) {
            if (node.equals(Nodes.ADMIN)) continue;
            if (checkAdmin(source, node)) return true;
        }
        return false;
    }

    public static boolean checkCommand(CommandSourceStack source, String node) {
        return check(source, node, true);
    }

    private static Boolean explicit(CommandSourceStack source, String node) {
        boolean whenTrue = check(source, node, true);
        boolean whenFalse = check(source, node, false);
        return whenTrue == whenFalse ? whenTrue : null;
    }
}
