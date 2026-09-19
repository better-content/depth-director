package com.bettercontent.depthdirector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class LocalityAccountingPolicyTest {
    @Test
    void splitAndRejoinConserveSpentAndRemainingBudgetExactly() {
        int[] remaining = DirectorPolicy.allocateConserved(47, List.of(2, 1));
        int[] spent = DirectorPolicy.allocateConserved(19, List.of(2, 1));
        assertArrayEquals(new int[]{31, 16}, remaining);
        assertArrayEquals(new int[]{13, 6}, spent);

        int[] resplit = DirectorPolicy.allocateConserved(remaining[0], List.of(1, 1));
        assertEquals(47, resplit[0] + resplit[1] + remaining[1]);
        assertEquals(19, spent[0] + spent[1]);
    }

    @Test
    void allocationDoesNotTouchPerPlayerPressureOrRecoveryState() {
        DirectorSavedData.Track track = new DirectorSavedData.Track();
        track.pressure(0.72);
        track.recoveryUntil(24_000L);
        DirectorPolicy.allocateConserved(64, List.of(1, 3));
        assertEquals(0.72, track.pressure());
        assertEquals(24_000L, track.recoveryUntil());
    }
}
