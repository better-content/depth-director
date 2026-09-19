package com.bettercontent.depthdirector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PursuitTargetPolicyTest {
    private static final UUID FIRST = new UUID(0L, 1L);
    private static final UUID SECOND = new UUID(0L, 2L);
    private static final UUID OUTSIDER = new UUID(0L, 3L);

    @Test
    void unavailablePursuitHandsOffToTheStableEligibleParticipant() {
        assertEquals(SECOND, DirectorPolicy.pursuitTarget(FIRST, List.of(FIRST, SECOND), List.of(SECOND)));
    }

    @Test
    void currentEligiblePursuitStaysSelectedAndOutsidersCannotBeSelected() {
        assertEquals(SECOND, DirectorPolicy.pursuitTarget(SECOND, List.of(FIRST, SECOND), List.of(FIRST, SECOND)));
        assertNull(DirectorPolicy.pursuitTarget(OUTSIDER, List.of(FIRST, SECOND), List.of(OUTSIDER)));
    }
}
