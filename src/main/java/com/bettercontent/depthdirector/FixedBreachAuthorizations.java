package com.bettercontent.depthdirector;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Session-scoped authorization for native and reconnecting Deep Void entrants. */
final class FixedBreachAuthorizations {
    private final Set<UUID> players = new HashSet<>();

    void clear() { players.clear(); }

    void dimensionChanged(UUID player, ResourceLocation source, ResourceLocation destination) {
        players.remove(player);
        if (FixedBreachEligibility.authorizesNativeEntry(source, destination)) players.add(player);
    }

    void loggedIn(UUID player, boolean naturalDimension, ResourceLocation dimension) {
        players.remove(player);
        if (!naturalDimension && FixedBreachEligibility.DEEP_VOID.equals(dimension)) players.add(player);
    }

    void loggedOut(UUID player) { players.remove(player); }

    boolean contains(UUID player) { return players.contains(player); }
}
