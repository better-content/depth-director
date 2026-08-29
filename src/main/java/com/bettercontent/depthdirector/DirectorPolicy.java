package com.bettercontent.depthdirector;

final class DirectorPolicy {
    static final int NATIVE_CADENCE_MIN = 240;
    static final int NATIVE_CADENCE_MAX = 420;
    static final int NATIVE_WARNING_MIN = 10;
    static final int NATIVE_WARNING_MAX = 24;
    static final int NATIVE_SURGE_SECONDS = 90;
    static final int NATIVE_RECOVERY_SECONDS = 90;
    static final int NATIVE_DEEP_BUDGET = 64;
    static final int NATIVE_DEEP_ACTIVE = 36;
    static final int NATIVE_PACKET_INTERVAL = 100;
    static final int MAX_QUEUED_PER_PLAYER = 6;

    private DirectorPolicy() {}

    static double cadenceSeconds(int minimum, int maximum, double jitter) {
        return DepthMath.lerp(minimum, maximum, jitter);
    }

    static double advancePressure(double pressure, double depth, double cadenceSeconds, boolean eligible,
                                  boolean routeSecured, boolean distressed, boolean downed,
                                  boolean recovering, boolean onSurface, int surfaceDecaySeconds) {
        if (onSurface) return DepthMath.clamp(pressure - 1.0 / Math.max(1, surfaceDecaySeconds), 0.0, 1.0);
        if (!eligible || routeSecured || distressed || downed || recovering) return DepthMath.clamp(pressure, 0.0, 1.0);
        return DepthMath.clamp(pressure + depth / Math.max(1.0, cadenceSeconds), 0.0, 1.0);
    }

    static int routeFailures(int current, boolean probeDue, boolean routeOpen) {
        if (!probeDue) return Math.max(0, current);
        return routeOpen ? 0 : Math.max(0, current) + 1;
    }

    static Profile scaleProfile(ProfileSpec spec, double depth, double warningRoll) {
        int warningRange = Math.max(0, spec.warningMaximumSeconds - spec.warningMinimumSeconds);
        int warning = spec.warningMinimumSeconds
                + Math.min(warningRange, (int) Math.floor(DepthMath.clamp(warningRoll, 0.0, Math.nextDown(1.0)) * (warningRange + 1)));
        int budget = (int) Math.round(DepthMath.lerp(8, spec.deepBudgetPerPlayer, depth));
        int active = (int) Math.round(DepthMath.lerp(6, spec.deepActiveTargetPerPlayer, depth));
        return new Profile(warning * 20, spec.surgeSeconds * 20, spec.recoverySeconds * 20,
                budget, active, spec.packetIntervalTicks, spec.maximizeDirections);
    }

    static PopulationLimits scaleForPlayers(Profile profile, int playerCount, int globalCap) {
        int players = Math.max(0, playerCount);
        return new PopulationLimits(profile.budgetPerPlayer * players,
                Math.min(Math.max(0, globalCap), profile.activeTargetPerPlayer * players));
    }

    static int packetSize(int active, int activeTarget, int playerCount) {
        return Math.max(0, Math.min(activeTarget - active, MAX_QUEUED_PER_PLAYER * Math.max(0, playerCount)));
    }

    static int packetInterval(int baseTicks, double healthRatio, double distressThreshold) {
        return Math.max(1, baseTicks) * (healthRatio < distressThreshold ? 3 : 1);
    }

    static int globalSpawnAllowance(int tickLimit, int secondLimit, int spawnedThisSecond,
                                    int globalCap, int globalPopulation) {
        return Math.max(0, Math.min(tickLimit, Math.min(secondLimit - spawnedThisSecond, globalCap - globalPopulation)));
    }

    static int roundRobinIndex(int offset, int attempt, int encounterCount) {
        return Math.floorMod(offset + attempt, Math.max(1, encounterCount));
    }

    static int nextRoundRobinOffset(int offset, int encounterCount) {
        return Math.floorMod(offset + 1, Math.max(1, encounterCount));
    }

    static int queuedWorkAfterTransition(int queuedWork, Phase phase) {
        return phase == Phase.RESCUE || phase == Phase.RECOVERY || phase == Phase.RETIRED
                ? 0 : Math.max(0, queuedWork);
    }

    static Phase transition(Phase phase, long now, long phaseUntil, boolean hasEligiblePlayers,
                            boolean routeOpen, boolean downed, int remainingBudget) {
        return switch (phase) {
            case WARNING -> now < phaseUntil ? Phase.WARNING
                    : hasEligiblePlayers && routeOpen ? Phase.SURGE : Phase.RETIRED;
            case SURGE -> downed ? Phase.RESCUE
                    : !hasEligiblePlayers || now >= phaseUntil || remainingBudget <= 0 ? Phase.RECOVERY : Phase.SURGE;
            case RESCUE -> downed ? Phase.RESCUE : Phase.RECOVERY;
            case RECOVERY -> now >= phaseUntil ? Phase.RETIRED : Phase.RECOVERY;
            case RETIRED -> Phase.RETIRED;
        };
    }

    static ProfileSpec nativeSpec() {
        return new ProfileSpec(NATIVE_WARNING_MIN, NATIVE_WARNING_MAX, NATIVE_SURGE_SECONDS,
                NATIVE_RECOVERY_SECONDS, NATIVE_DEEP_BUDGET, NATIVE_DEEP_ACTIVE,
                NATIVE_PACKET_INTERVAL, false);
    }

    enum Phase { WARNING, SURGE, RESCUE, RECOVERY, RETIRED }

    record ProfileSpec(int warningMinimumSeconds, int warningMaximumSeconds, int surgeSeconds,
                       int recoverySeconds, int deepBudgetPerPlayer, int deepActiveTargetPerPlayer,
                       int packetIntervalTicks, boolean maximizeDirections) {}

    record Profile(int warningTicks, int surgeTicks, int recoveryTicks, int budgetPerPlayer,
                   int activeTargetPerPlayer, int packetIntervalTicks, boolean maximizeDirections) {}

    record PopulationLimits(int budget, int activeTarget) {}
}
