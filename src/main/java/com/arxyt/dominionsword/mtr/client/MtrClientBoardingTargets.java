package com.arxyt.dominionsword.mtr.client;

import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client mirror of the exact server-reserved doorway for each boarding unit. */
public final class MtrClientBoardingTargets {
    private static final long TTL_MS = 2_000L;
    private static final Map<Key, Target> TARGETS = new ConcurrentHashMap<>();

    private MtrClientBoardingTargets() {}

    public static void accept(UUID unitId, UUID proxyId, Vec3 position) {
        TARGETS.put(new Key(unitId, proxyId), new Target(position, System.currentTimeMillis() + TTL_MS));
    }

    public static Vec3 get(UUID unitId, UUID proxyId) {
        Key key = new Key(unitId, proxyId);
        Target target = TARGETS.get(key);
        if (target == null) return null;
        if (target.expiresAt < System.currentTimeMillis()) {
            TARGETS.remove(key, target);
            return null;
        }
        return target.position;
    }

    private record Key(UUID unitId, UUID proxyId) {}
    private record Target(Vec3 position, long expiresAt) {}
}
