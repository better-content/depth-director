package com.bettercontent.depthdirector;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }
}
