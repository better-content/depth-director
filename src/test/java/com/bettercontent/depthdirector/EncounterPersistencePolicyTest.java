package com.bettercontent.depthdirector;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncounterPersistencePolicyTest {
    @Test
    void splitBudgetConservationSurvivesMultipleGroups() {
        int remaining = 137;
        int spent = 59;
        List<Integer> weights = List.of(1, 2, 3, 4);
        int[] remainingParts = DirectorPolicy.allocateConserved(remaining, weights);
        int[] spentParts = DirectorPolicy.allocateConserved(spent, weights);
        assertEquals(remaining, sum(remainingParts));
        assertEquals(spent, sum(spentParts));
    }

    @Test
    void persistedPhaseTimingUsesRemainingDurationAfterSuspension() {
        long now = 10_000L;
        long phaseUntil = 10_240L;
        long suspendedAt = 10_090L;
        long remaining = Math.max(0L, phaseUntil - suspendedAt);
        assertEquals(150L, remaining);
        assertEquals(suspendedAt + remaining, phaseUntil);
        assertEquals(now + remaining, now + 150L);
    }

    @Test
    void restoresInFlightTelegraphAndPacketAccountingWithoutExtendingItsDeadline() {
        ResourceLocation mob = new ResourceLocation("example", "heavy");
        BlockPos corridor = new BlockPos(12, -38, 27);

        EncounterPersistencePolicy.PacketState restored = EncounterPersistencePolicy.restorePacket(
                1, 10_040L, corridor, 5, true, 3, true,
                Map.of(mob, 1), true, Map.of(mob, 1));

        assertEquals(10_040L, restored.telegraphUntil());
        assertEquals(corridor, restored.position());
        assertEquals(5, restored.sector());
        assertEquals(3, restored.queued());
        assertEquals(true, restored.continuation());
        assertEquals(true, restored.heavySpawned());
        assertEquals(Map.of(mob, 1), restored.packetCounts());
    }

    @Test
    void malformedOrLegacyPacketStateCannotResumeQueuedWorkWithoutItsCorridor() {
        EncounterPersistencePolicy.PacketState restored = EncounterPersistencePolicy.restorePacket(
                0, 10_040L, null, 2, true, 4, true,
                Map.of(), false, Map.of());

        assertEquals(-1L, restored.telegraphUntil());
        assertEquals(null, restored.position());
        assertEquals(-1, restored.sector());
        assertEquals(0, restored.queued());
        assertEquals(false, restored.continuation());
        assertEquals(false, restored.heavySpawned());
        assertEquals(Map.of(), restored.packetCounts());
    }

    @Test
    void missingPerPacketCountsFallsBackToEncounterCountsConservatively() {
        ResourceLocation mob = new ResourceLocation("example", "heavy");
        EncounterPersistencePolicy.PacketState restored = EncounterPersistencePolicy.restorePacket(
                1, -1L, new BlockPos(1, 2, 3), -1, false, 2, true,
                Map.of(), false, Map.of(mob, 2));

        assertEquals(Map.of(mob, 2), restored.packetCounts());
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }
}
