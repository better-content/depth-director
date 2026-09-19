package com.bettercontent.depthdirector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncounterCorridorPolicyTest {
    @Test void rejectedWarnedApproachDoesNotAdvanceSector() {
        assertEquals(3, DirectorPolicy.nextSectorAfterSpawn(3, true, false));
        assertEquals(4, DirectorPolicy.nextSectorAfterSpawn(3, true, true));
        assertEquals(-1, DirectorPolicy.nextSectorAfterSpawn(-1, false, true));
    }

    @Test void repeatedLocalitySplitsConserveBothBudgetLedgers() {
        int[] remaining = DirectorPolicy.allocateConserved(53, List.of(3, 2, 1));
        int[] spent = DirectorPolicy.allocateConserved(31, List.of(3, 2, 1));
        int[] rejoined = DirectorPolicy.allocateConserved(remaining[0], List.of(1, 1));
        assertEquals(53, remaining[0] + remaining[1] + remaining[2]);
        assertEquals(31, spent[0] + spent[1] + spent[2]);
        assertEquals(remaining[0], rejoined[0] + rejoined[1]);
    }
}
