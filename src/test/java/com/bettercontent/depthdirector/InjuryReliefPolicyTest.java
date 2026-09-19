package com.bettercontent.depthdirector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InjuryReliefPolicyTest {
    @Test
    void newMaimCreatesReliefThatDecaysEvenWhenItRemainsOrIsTreated() {
        double relief = DirectorPolicy.advanceInjuryRelief(0.0, 0, 3, 180);
        assertEquals(3.0, relief);

        double untreated = DirectorPolicy.advanceInjuryRelief(relief, 3, 3, 180);
        double treated = DirectorPolicy.advanceInjuryRelief(relief, 3, 0, 180);
        assertEquals(untreated, treated, 1.0e-12, "treatment must not erase the Director relief early");

        for (int second = 0; second < 540; second++) {
            untreated = DirectorPolicy.advanceInjuryRelief(untreated, 3, 3, 180);
        }
        assertTrue(untreated < 0.15, "three 180-second time constants approach the no-injury baseline");
    }

    @Test
    void reliefDoesNotChangeActiveBudgetOrRecoveryAccounting() {
        DirectorPolicy.Profile profile = DirectorPolicy.scaleProfile(DirectorPolicy.nativeSpec(), 1.0, 0.5);
        DirectorPolicy.PopulationLimits before = DirectorPolicy.scaleForPlayers(profile, 2, 160);
        double relief = DirectorPolicy.advanceInjuryRelief(0.0, 0, 6, 180);
        DirectorPolicy.PopulationLimits after = DirectorPolicy.scaleForPlayers(profile, 2, 160);

        assertTrue(relief > 0.0);
        assertEquals(before, after, "injury pacing changes no active or remaining encounter budget");
        assertEquals(DirectorPolicy.Phase.RECOVERY, DirectorPolicy.transition(DirectorPolicy.Phase.SURGE,
                100, 100, true, true, after.budget()));
    }
}
