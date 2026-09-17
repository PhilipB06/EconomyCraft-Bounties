package com.reazip.economycraft.bounties;

import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class PermissionCompat {
    private static final String[] LEGACY_NAMES = {"hasPermission", "hasPermissionLevel", "method_9259"};
    private static final int GAMEMASTER_LEVEL = 2;

    private static final Method LEGACY_HAS_PERMISSION_LEVEL = findLegacyHasPermissionLevel();
    private static final Method MODERN_PERMISSIONS = findModernPermissions();
    private static final Method MODERN_HAS_PERMISSION =
            MODERN_PERMISSIONS == null ? null : findHasPermission(MODERN_PERMISSIONS.getReturnType());
    private static final Object MODERN_GAMEMASTER = findModernGamemaster(MODERN_HAS_PERMISSION);

    static {
        if (LEGACY_HAS_PERMISSION_LEVEL == null && MODERN_GAMEMASTER == null) {
            EconomyCraftBounties.LOGGER.error("[EconomyCraft Bounties] No supported operator-permission API was "
                    + "found on this Minecraft version; operator-only commands will be unavailable to everyone.");
        }
    }

    private PermissionCompat() {
    }

    public static boolean isOperator(CommandSourceStack source) {
        try {
            if (LEGACY_HAS_PERMISSION_LEVEL != null) {
                return (boolean) LEGACY_HAS_PERMISSION_LEVEL.invoke(source, GAMEMASTER_LEVEL);
            }
            if (MODERN_GAMEMASTER != null) {
                Object permissionSet = MODERN_PERMISSIONS.invoke(source);
                return (boolean) MODERN_HAS_PERMISSION.invoke(permissionSet, MODERN_GAMEMASTER);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            EconomyCraftBounties.LOGGER.error("[EconomyCraft Bounties] Could not check operator permission.", e);
        }
        return false;
    }

    private static Method findLegacyHasPermissionLevel() {
        for (String name : LEGACY_NAMES) {
            Method candidate = findMethod(CommandSourceStack.class, name, int.class);
            if (candidate != null && candidate.getReturnType() == boolean.class) return candidate;
        }
        return null;
    }

    private static Method findModernPermissions() {
        for (Method candidate : CommandSourceStack.class.getMethods()) {
            if (candidate.getParameterCount() != 0 || Modifier.isStatic(candidate.getModifiers())) continue;
            if (!candidate.getReturnType().isInterface()) continue;
            if (findHasPermission(candidate.getReturnType()) != null) return candidate;
        }
        return null;
    }

    private static Method findHasPermission(Class<?> permissionSet) {
        for (Method candidate : permissionSet.getMethods()) {
            if (candidate.getParameterCount() == 1
                    && candidate.getReturnType() == boolean.class
                    && "hasPermission".equals(candidate.getName())
                    && !candidate.getParameterTypes()[0].isPrimitive()) {
                return candidate;
            }
        }
        return null;
    }

    private static Object findModernGamemaster(Method hasPermission) {
        if (hasPermission == null) return null;
        Class<?> permission = hasPermission.getParameterTypes()[0];
        for (Class<?> nested : permission.getClasses()) {
            if (!permission.isAssignableFrom(nested)) continue;
            for (Constructor<?> constructor : nested.getConstructors()) {
                if (constructor.getParameterCount() != 1) continue;
                Class<?> parameter = constructor.getParameterTypes()[0];
                if (!parameter.isEnum()) continue;
                Object level = commandLevel(parameter);
                if (level == null) continue;
                try {
                    return constructor.newInstance(level);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static Object commandLevel(Class<?> permissionLevel) {
        for (Method candidate : permissionLevel.getMethods()) {
            if (Modifier.isStatic(candidate.getModifiers())
                    && candidate.getReturnType() == permissionLevel
                    && candidate.getParameterCount() == 1
                    && candidate.getParameterTypes()[0] == int.class) {
                try {
                    return candidate.invoke(null, GAMEMASTER_LEVEL);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        Object[] constants = permissionLevel.getEnumConstants();
        return constants != null && constants.length > GAMEMASTER_LEVEL ? constants[GAMEMASTER_LEVEL] : null;
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
