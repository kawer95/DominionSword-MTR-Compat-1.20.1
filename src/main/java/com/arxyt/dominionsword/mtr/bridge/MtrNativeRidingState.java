package com.arxyt.dominionsword.mtr.bridge;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reads MTR's authoritative Minecraft-server tracking map, which is cleared by its native dismount packet. */
public final class MtrNativeRidingState {
    private static final Set<UUID> RIDING_PLAYERS = ConcurrentHashMap.newKeySet();

    private MtrNativeRidingState() {}

    public static boolean isPlayerRiding(UUID playerId) {
        return playerId != null && RIDING_PLAYERS.contains(playerId);
    }

    public static void update(UUID playerId, boolean riding) {
        if (playerId == null) return;
        if (riding) RIDING_PLAYERS.add(playerId); else RIDING_PLAYERS.remove(playerId);
    }

    public static void clear() { RIDING_PLAYERS.clear(); }
}
