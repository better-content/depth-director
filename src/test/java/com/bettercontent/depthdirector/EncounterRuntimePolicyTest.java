package com.bettercontent.depthdirector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EncounterRuntimePolicyTest {
    @Test void pursuitFollowsMovingEligibleParticipantWithoutChangingEncounterIdentity() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(second, DirectorPolicy.pursuitTarget(first, List.of(first, second), List.of(second)));
        assertEquals(first, DirectorPolicy.pursuitTarget(second, List.of(first, second), List.of(first)));
    }

    @Test void warningAndSurgeSuspendWhenRouteOrTargetDisappears() {
        assertEquals(DirectorPolicy.Phase.SUSPENDED,
                DirectorPolicy.transition(DirectorPolicy.Phase.WARNING, 100, 100, true, false, 10));
        assertEquals(DirectorPolicy.Phase.SUSPENDED,
                DirectorPolicy.transition(DirectorPolicy.Phase.SURGE, 20, 100, false, true, 10));
        assertEquals(DirectorPolicy.Phase.SURGE,
                DirectorPolicy.transition(DirectorPolicy.Phase.SURGE, 20, 100, true, true, 10));
    }

    @Test void retirementCannotLeaveQueuedWorkOrEncounterAttribution() {
        assertEquals(0, DirectorPolicy.queuedWorkAfterTransition(4, DirectorPolicy.Phase.RECOVERY));
        assertEquals(0, DirectorPolicy.queuedWorkAfterTransition(4, DirectorPolicy.Phase.RETIRED));
        assertEquals(4, DirectorPolicy.queuedWorkAfterTransition(4, DirectorPolicy.Phase.SURGE));
    }
}
