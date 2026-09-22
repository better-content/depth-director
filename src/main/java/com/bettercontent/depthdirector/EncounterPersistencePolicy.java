package com.bettercontent.depthdirector;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** Validates the in-flight packet state that must survive an encounter reload. */
final class EncounterPersistencePolicy {
    private EncounterPersistencePolicy() {}

    static PacketState restorePacket(int version, long telegraphUntil, BlockPos position, int sector,
                                     boolean continuation, int queued, boolean heavySpawned,
                                     Map<ResourceLocation, Integer> packetCounts,
                                     boolean packetCountsPresent,
                                     Map<ResourceLocation, Integer> encounterCounts) {
        boolean validPosition = version == 1 && position != null && sector >= -1 && sector < 8;
        if (!validPosition) {
            return new PacketState(-1L, null, -1, false, 0, false, Map.of());
        }

        Map<ResourceLocation, Integer> counts = sanitizeCounts(packetCountsPresent
                ? packetCounts : queued > 0 ? encounterCounts : Map.of());
        return new PacketState(telegraphUntil >= 0L ? telegraphUntil : -1L,
                position, sector, telegraphUntil >= 0L && continuation,
                Math.max(0, queued), heavySpawned, counts);
    }

    private static Map<ResourceLocation, Integer> sanitizeCounts(Map<ResourceLocation, Integer> counts) {
        Map<ResourceLocation, Integer> sanitized = new HashMap<>();
        counts.forEach((entity, count) -> {
            if (entity != null && count != null && count > 0) sanitized.put(entity, count);
        });
        return Map.copyOf(sanitized);
    }

    record PacketState(long telegraphUntil, BlockPos position, int sector, boolean continuation,
                       int queued, boolean heavySpawned,
                       Map<ResourceLocation, Integer> packetCounts) {}
}
