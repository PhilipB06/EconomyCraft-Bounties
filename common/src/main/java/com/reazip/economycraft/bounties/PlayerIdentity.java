package com.reazip.economycraft.bounties;

import com.mojang.authlib.GameProfile;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public record PlayerIdentity(UUID id, String name) {
    static PlayerIdentity fromUnknown(Object value) {
        if (value == null) throw new IllegalArgumentException("Player identity is null");

        UUID id = idOf(value);
        String name = nameOf(value);
        if (id == null) throw new IllegalArgumentException("Could not read player UUID from " + value.getClass());
        if (name == null || name.isBlank()) name = id.toString();
        return new PlayerIdentity(id, name);
    }

    static UUID idOf(Object value) {
        return readUuid(invoke(value, "id", "getId", "uuid", "getUuid"));
    }

    static String nameOf(Object value) {
        return readString(invoke(value, "name", "getName"));
    }

    private static Object invoke(Object value, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = value.getClass().getMethod(methodName);
                return method.invoke(value);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static UUID readUuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value instanceof Optional<?> optional) return readUuid(optional.orElse(null));
        if (value instanceof GameProfile profile) return readUuid(invoke(profile, "id", "getId"));
        return null;
    }

    private static String readString(Object value) {
        if (value instanceof String string) return string;
        if (value instanceof Optional<?> optional) return readString(optional.orElse(null));
        return null;
    }
}
