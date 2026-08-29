package com.bettercontent.depthdirector;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorSimulationTest {
    private static final int SEEDS = 1_024;
    private static final int SEA_LEVEL = 63;
    private static final int MINIMUM_Y = -64;
    private static final int DEEP_Y = -56;
    private static final int GLOBAL_CAP = 160;
    private static final Map<String, EcologyDefinition> ECOLOGIES = loadEcologies();

    @Test
    void fixedSeedCorpusIsDeterministicAndTransportAgnostic() {
        int[] trajectory = {-56, -54, -52, -55, -56, -53, -51, -56};
        for (ProfileCase profile : profiles()) {
            for (long seed = 0; seed < SEEDS; seed++) {
                Timeline first = timeline(profile, seed, trajectory, Transport.WALKING);
                assertEquals(first, timeline(profile, seed, trajectory, Transport.WALKING),
                        profile.name + " seed " + seed);
                assertEquals(first, timeline(profile, seed, trajectory, Transport.CRAWLER_LABELLED),
                        "walking and crawler-labelled movement must have identical policy inputs");
            }
        }
    }

    @Test
    void encounterCadenceFallsMonotonicallyWithDepthAndMeetsDeepBands() {
        Map<String, double[]> bands = Map.of(
                "undead", new double[]{2.5, 6.5},
                "carrion", new double[]{3.0, 8.0},
                "spirits", new double[]{4.0, 9.5},
                "end", new double[]{4.5, 10.5},
                "sculk", new double[]{6.5, 12.5},
                "native", new double[]{3.5, 8.0});
        double deep = DepthMath.depthFactor(DEEP_Y, SEA_LEVEL, MINIMUM_Y);
        double middle = DepthMath.depthFactor(0, SEA_LEVEL, MINIMUM_Y);
        double shallow = DepthMath.depthFactor(32, SEA_LEVEL, MINIMUM_Y);

        for (ProfileCase profile : profiles()) {
            double[] band = bands.get(profile.name);
            for (long seed = 0; seed < SEEDS; seed++) {
                double jitter = RandomSource.create(seed).nextDouble();
                int deepSeconds = triggerSeconds(profile, deep, jitter);
                int middleSeconds = triggerSeconds(profile, middle, jitter);
                int shallowSeconds = triggerSeconds(profile, shallow, jitter);
                assertTrue(deepSeconds <= middleSeconds && middleSeconds <= shallowSeconds,
                        profile.name + " cadence must decrease monotonically with depth");
                assertTrue((double) middleSeconds / deepSeconds >= 1.8,
                        profile.name + " deep frequency must be at least 1.8x Y 0");
                assertTrue((double) shallowSeconds / deepSeconds >= 5.0,
                        profile.name + " deep frequency must be at least 5x Y 32");
                double minutes = deepSeconds / 60.0;
                assertTrue(minutes >= band[0] && minutes <= band[1],
                        profile.name + " seed " + seed + " cadence " + minutes + " outside provisional band");
            }
        }
    }

    @Test
    void deepProfilesAndNearbyPlayerScalingMeetTargets() {
        double depth = DepthMath.depthFactor(DEEP_Y, SEA_LEVEL, MINIMUM_Y);
        for (ProfileCase profileCase : profiles()) {
            DirectorPolicy.Profile profile = DirectorPolicy.scaleProfile(profileCase.spec, depth, 0.5);
            assertTrue(profile.activeTargetPerPlayer() >= 30 && profile.activeTargetPerPlayer() <= 44,
                    profileCase.name + " active target");
            assertTrue(profile.budgetPerPlayer() >= 56 && profile.budgetPerPlayer() <= 100,
                    profileCase.name + " budget");
            for (int players = 1; players <= 8; players++) {
                DirectorPolicy.PopulationLimits limits = DirectorPolicy.scaleForPlayers(profile, players, GLOBAL_CAP);
                assertEquals(profile.budgetPerPlayer() * players, limits.budget());
                assertEquals(Math.min(GLOBAL_CAP, profile.activeTargetPerPlayer() * players), limits.activeTarget());
            }
        }
    }

    @Test
    void rosterSimulationReachesPopulationBandsWithoutBreakingLimits() {
        double depth = DepthMath.depthFactor(DEEP_Y, SEA_LEVEL, MINIMUM_Y);
        List<String> violations = new ArrayList<>();
        for (ProfileCase profileCase : profiles()) {
            List<Integer> peaks = new ArrayList<>(SEEDS);
            int target = DirectorPolicy.scaleForPlayers(
                    DirectorPolicy.scaleProfile(profileCase.spec, depth, 0.5), 3, GLOBAL_CAP).activeTarget();
            for (long seed = 0; seed < SEEDS; seed++) peaks.add(simulateRoster(profileCase, seed, depth));
            peaks.sort(Comparator.naturalOrder());
            int fifthPercentile = peaks.get((int) Math.floor((peaks.size() - 1) * 0.05));
            int median = peaks.get(peaks.size() / 2);
            if (median < Math.ceil(target * 0.80)) {
                violations.add(profileCase.name + " median peak " + median + "/" + target);
            }
            if (fifthPercentile < Math.ceil(target * 0.60)) {
                violations.add(profileCase.name + " fifth-percentile peak " + fifthPercentile + "/" + target);
            }
        }
        assertTrue(violations.isEmpty(), String.join(", ", violations));
    }

    @Test
    void routeDistressRescueRecoveryAndSurfaceRulesAreExact() {
        int failures = 0;
        failures = DirectorPolicy.routeFailures(failures, true, false);
        failures = DirectorPolicy.routeFailures(failures, true, false);
        failures = DirectorPolicy.routeFailures(failures, true, false);
        assertEquals(3, failures);
        double frozen = 0.40;
        for (int second = 0; second < 120; second++) {
            frozen = DirectorPolicy.advancePressure(frozen, 1.0, 180.0,
                    true, failures >= 3, false, false, false, false, 480);
        }
        assertEquals(0.40, frozen);
        failures = DirectorPolicy.routeFailures(failures, true, true);
        double resumed = DirectorPolicy.advancePressure(frozen, 1.0, 180.0,
                true, failures >= 3, false, false, false, false, 480);
        assertTrue(resumed > frozen && resumed < 0.5, "an open route resumes pressure without accumulated burst");

        assertEquals(300, DirectorPolicy.packetInterval(100, 0.34, 0.35));
        assertEquals(100, DirectorPolicy.packetInterval(100, 0.35, 0.35));
        DirectorPolicy.Phase rescue = DirectorPolicy.transition(DirectorPolicy.Phase.SURGE,
                20, 200, true, true, true, 50);
        assertEquals(DirectorPolicy.Phase.RESCUE, rescue);
        assertEquals(0, DirectorPolicy.queuedWorkAfterTransition(6, rescue));
        assertEquals(DirectorPolicy.Phase.RECOVERY, DirectorPolicy.transition(rescue,
                21, 200, true, true, false, 0));
        assertEquals(0.0, DirectorPolicy.advancePressure(0.0, 1.0, 180.0,
                true, false, false, false, true, false, 480));

        double surfacePressure = 1.0;
        for (int second = 0; second < 480; second++) {
            surfacePressure = DirectorPolicy.advancePressure(surfacePressure, 0.0, 1.0,
                    false, false, false, false, false, true, 480);
        }
        assertEquals(0.0, surfacePressure, 1.0e-12);
    }

    @Test
    void packetGlobalBudgetHeavyAndRoundRobinLimitsAreHard() {
        assertEquals(18, DirectorPolicy.packetSize(0, 100, 3));
        assertEquals(4, DirectorPolicy.packetSize(96, 100, 3));
        assertEquals(8, DirectorPolicy.globalSpawnAllowance(8, 24, 0, 160, 0));
        assertEquals(4, DirectorPolicy.globalSpawnAllowance(8, 24, 20, 160, 0));
        assertEquals(2, DirectorPolicy.globalSpawnAllowance(8, 24, 0, 160, 158));

        int[] service = new int[3];
        int offset = 0;
        for (int tick = 0; tick < 30; tick++) {
            for (int attempt = 0; attempt < 8; attempt++) {
                service[DirectorPolicy.roundRobinIndex(offset, attempt, service.length)]++;
            }
            offset = DirectorPolicy.nextRoundRobinOffset(offset, service.length);
        }
        int minimum = Math.min(service[0], Math.min(service[1], service[2]));
        int maximum = Math.max(service[0], Math.max(service[1], service[2]));
        assertTrue(maximum - minimum <= 1, "round-robin service must remain fair");

        EcologyDefinition undead = ECOLOGIES.get("undead");
        RandomSource random = RandomSource.create(83L);
        int heavy = 0;
        for (int packet = 0; packet < 100; packet++) {
            boolean allowHeavy = true;
            int packetHeavy = 0;
            for (int slot = 0; slot < DirectorPolicy.MAX_QUEUED_PER_PLAYER; slot++) {
                EcologyDefinition.Entry entry = undead.pick(random, 1.0, allowHeavy, ignored -> true);
                assertNotNull(entry);
                if (entry.role() == EcologyDefinition.Role.HEAVY) {
                    packetHeavy++;
                    heavy++;
                    allowHeavy = false;
                }
            }
            assertTrue(packetHeavy <= 1);
        }
        assertTrue(heavy > 0, "the seeded corpus must exercise heavy selection");
    }

    @Test
    void optionalRosterEntriesAreEligibilityAware() {
        EcologyDefinition sculk = ECOLOGIES.get("sculk");
        assertDoesNotThrow(() -> {
            for (long seed = 0; seed < SEEDS; seed++) {
                assertNull(sculk.pick(RandomSource.create(seed), 1.0, true, ignored -> false));
            }
        });

        EcologyDefinition undead = ECOLOGIES.get("undead");
        for (long seed = 0; seed < SEEDS; seed++) {
            EcologyDefinition.Entry entry = undead.pick(RandomSource.create(seed), 1.0, true,
                    id -> id.getNamespace().equals("quark"));
            assertNotNull(entry);
            assertEquals("quark", entry.entity().getNamespace());
        }
        EcologyDefinition.Entry noHeavy = undead.pick(RandomSource.create(1L), 1.0, false,
                id -> id.getPath().contains("bruiser"));
        assertNull(noHeavy);
    }

    @Test
    void authoredRostersExcludeBaseZombieAndSkeletonAndCarryExplicitLimits() {
        Set<ResourceLocation> excluded = Set.of(
                new ResourceLocation("minecraft", "zombie"),
                new ResourceLocation("minecraft", "skeleton"));
        for (Map.Entry<String, EcologyDefinition> ecology : ECOLOGIES.entrySet()) {
            assertTrue(ecology.getValue().roster().stream().noneMatch(entry -> excluded.contains(entry.entity())),
                    ecology.getKey() + " must not direct vanilla zombies or skeletons");
            for (EcologyDefinition.Entry entry : ecology.getValue().roster()) {
                assertTrue(entry.cost() > 0, entry.entity() + " must consume budget");
                assertTrue(entry.maximumPerPacket() > 0, entry.entity() + " must have a packet cap");
                assertTrue(entry.maximumPerEncounter() > 0, entry.entity() + " must have an encounter cap");
            }
        }
    }

    @Test
    void rosterSchemaDefaultsRemainCompatibleAndRejectsInvalidLimits() {
        String prefix = "{\"cadence_seconds\":{\"minimum\":180,\"maximum\":300},"
                + "\"warning_seconds\":{\"minimum\":10,\"maximum\":20},"
                + "\"surge_seconds\":60,\"recovery_seconds\":60,"
                + "\"deep_budget_per_player\":60,\"deep_active_target_per_player\":30,"
                + "\"packet_interval_ticks\":100,\"roster\":[";
        EcologyDefinition parsed = EcologyDefinition.parse(new ResourceLocation("depth_director", "schema"),
                JsonParser.parseString(prefix
                        + "{\"entity\":\"example:defaulted\",\"role\":\"line\"},"
                        + "{\"entity\":\"example:limited\",\"role\":\"common\",\"cost\":5,"
                        + "\"maximum_per_packet\":2,\"maximum_per_encounter\":3}]}" ).getAsJsonObject());
        EcologyDefinition.Entry defaulted = parsed.roster().get(0);
        assertEquals(EcologyDefinition.Role.LINE.cost(), defaulted.cost());
        assertTrue(defaulted.allowsPacketCount(10_000));
        assertTrue(defaulted.allowsEncounterCount(10_000, 4));
        EcologyDefinition.Entry limited = parsed.roster().get(1);
        assertEquals(5, limited.cost());
        assertTrue(limited.allowsPacketCount(1));
        assertTrue(!limited.allowsPacketCount(2));
        assertTrue(limited.allowsEncounterCount(11, 4));
        assertTrue(!limited.allowsEncounterCount(12, 4));

        assertThrows(IllegalArgumentException.class, () -> EcologyDefinition.parse(
                new ResourceLocation("depth_director", "invalid"),
                JsonParser.parseString(prefix
                        + "{\"entity\":\"example:bad\",\"role\":\"common\","
                        + "\"maximum_per_packet\":-1}]}" ).getAsJsonObject()));
    }

    @Test
    void eachSyntheticNoiseEcologyOwnsMeaningfulTerritory() {
        Map<String, Integer> primaryCounts = new LinkedHashMap<>();
        ECOLOGIES.keySet().forEach(name -> primaryCounts.put(name, 0));
        int samples = 0;
        for (int x = -48; x <= 48; x++) {
            for (int z = -48; z <= 48; z++) {
                double sampleX = x * 64.0;
                double sampleZ = z * 64.0;
                EcologyDefinition primary = ECOLOGIES.values().stream().max(Comparator.comparingDouble(ecology ->
                        EcologyNoise.sample(0x5EEDL, ecology.id(), sampleX, sampleZ, ecology.noiseScale())))
                        .orElseThrow();
                primaryCounts.merge(primary.id().getPath(), 1, Integer::sum);
                samples++;
            }
        }
        int total = samples;
        primaryCounts.forEach((name, count) -> assertTrue(count >= total / 10,
                name + " synthetic territory share is too small: " + count + "/" + total));
    }

    private static Timeline timeline(ProfileCase profile, long seed, int[] trajectory, Transport transport) {
        // Transport is deliberately not a policy input; only the shared position trajectory is consumed.
        RandomSource random = RandomSource.create(seed);
        List<Integer> triggers = new ArrayList<>();
        List<DirectorPolicy.Phase> phases = new ArrayList<>();
        int now = 0;
        for (int encounter = 0; encounter < 4; encounter++) {
            double pressure = 0.0;
            double jitter = random.nextDouble();
            double cadence = DirectorPolicy.cadenceSeconds(profile.cadenceMinimum, profile.cadenceMaximum, jitter);
            while (pressure < 1.0) {
                int y = trajectory[now % trajectory.length];
                pressure = DirectorPolicy.advancePressure(pressure,
                        DepthMath.depthFactor(y, SEA_LEVEL, MINIMUM_Y), cadence,
                        true, false, false, false, false, false, 480);
                now++;
            }
            triggers.add(now);
            DirectorPolicy.Profile scaled = DirectorPolicy.scaleProfile(profile.spec,
                    DepthMath.depthFactor(trajectory[now % trajectory.length], SEA_LEVEL, MINIMUM_Y), random.nextDouble());
            phases.add(DirectorPolicy.transition(DirectorPolicy.Phase.WARNING,
                    now + scaled.warningTicks(), now + scaled.warningTicks(), true, true, false, scaled.budgetPerPlayer()));
            now += scaled.warningTicks() / 20 + scaled.surgeTicks() / 20 + scaled.recoveryTicks() / 20;
        }
        return new Timeline(List.copyOf(triggers), List.copyOf(phases));
    }

    private static int triggerSeconds(ProfileCase profile, double depth, double jitter) {
        double cadence = DirectorPolicy.cadenceSeconds(profile.cadenceMinimum, profile.cadenceMaximum, jitter);
        double pressure = 0.0;
        int seconds = 0;
        while (pressure < 1.0) {
            pressure = DirectorPolicy.advancePressure(pressure, depth, cadence,
                    true, false, false, false, false, false, 480);
            seconds++;
        }
        return seconds;
    }

    private static int simulateRoster(ProfileCase profileCase, long seed, double depth) {
        RandomSource random = RandomSource.create(seed);
        DirectorPolicy.Profile profile = DirectorPolicy.scaleProfile(profileCase.spec, depth, random.nextDouble());
        DirectorPolicy.PopulationLimits limits = DirectorPolicy.scaleForPlayers(profile, 3, GLOBAL_CAP);
        int remainingBudget = limits.budget();
        int population = 0;
        int queued = 0;
        int nextPacket = 0;
        int spawnedThisSecond = 0;
        int packetHeavy = 0;
        Map<ResourceLocation, Integer> packetCounts = new HashMap<>();
        Map<ResourceLocation, Integer> encounterCounts = new HashMap<>();
        for (int tick = 0; tick < profile.surgeTicks() && remainingBudget > 0 && population < limits.activeTarget(); tick++) {
            if (tick % 20 == 0) spawnedThisSecond = 0;
            if (tick >= nextPacket && queued == 0) {
                queued = DirectorPolicy.packetSize(population, limits.activeTarget(), 3);
                assertTrue(queued <= DirectorPolicy.MAX_QUEUED_PER_PLAYER * 3);
                packetHeavy = 0;
                packetCounts.clear();
                nextPacket = tick + profile.packetIntervalTicks();
            }
            int allowance = DirectorPolicy.globalSpawnAllowance(8, 24, spawnedThisSecond, GLOBAL_CAP, population);
            assertTrue(allowance <= 8);
            int spawnedThisTick = 0;
            while (allowance-- > 0 && queued > 0 && remainingBudget > 0) {
                int availableBudget = remainingBudget;
                EcologyDefinition.Entry entry = profileCase.ecology == null
                        ? nativeEntry(random, remainingBudget)
                        : profileCase.ecology.pick(random, depth, packetHeavy == 0,
                        ignored -> true, candidate -> candidate.cost() <= availableBudget
                                && candidate.allowsPacketCount(packetCounts.getOrDefault(candidate.entity(), 0))
                                && candidate.allowsEncounterCount(
                                encounterCounts.getOrDefault(candidate.entity(), 0), 3));
                queued--;
                if (entry == null || entry.cost() > remainingBudget) continue;
                if (entry.role() == EcologyDefinition.Role.HEAVY) packetHeavy++;
                assertTrue(packetHeavy <= 1);
                packetCounts.merge(entry.entity(), 1, Integer::sum);
                encounterCounts.merge(entry.entity(), 1, Integer::sum);
                assertTrue(entry.allowsPacketCount(packetCounts.get(entry.entity()) - 1));
                assertTrue(entry.allowsEncounterCount(encounterCounts.get(entry.entity()) - 1, 3));
                remainingBudget -= entry.cost();
                population++;
                spawnedThisSecond++;
                spawnedThisTick++;
                assertTrue(population <= GLOBAL_CAP);
                assertTrue(remainingBudget >= 0);
            }
            assertTrue(spawnedThisTick <= 8);
            assertTrue(spawnedThisSecond <= 24);
        }
        return population;
    }

    private static EcologyDefinition.Entry nativeEntry(RandomSource random, int maximumCost) {
        int cost = 1 + random.nextInt(2);
        if (cost > maximumCost) return null;
        EcologyDefinition.Role role = cost == 1 ? EcologyDefinition.Role.COMMON : EcologyDefinition.Role.LINE;
        return new EcologyDefinition.Entry(new ResourceLocation("minecraft", "zombie"), role, 0.0, 1);
    }

    private static List<ProfileCase> profiles() {
        List<ProfileCase> result = new ArrayList<>();
        ECOLOGIES.forEach((name, ecology) -> result.add(new ProfileCase(name, ecology,
                ecology.cadenceMinimumSeconds(), ecology.cadenceMaximumSeconds(), spec(ecology))));
        result.add(new ProfileCase("native", null, DirectorPolicy.NATIVE_CADENCE_MIN,
                DirectorPolicy.NATIVE_CADENCE_MAX, DirectorPolicy.nativeSpec()));
        return result;
    }

    private static DirectorPolicy.ProfileSpec spec(EcologyDefinition ecology) {
        return new DirectorPolicy.ProfileSpec(ecology.warningMinimumSeconds(), ecology.warningMaximumSeconds(),
                ecology.surgeSeconds(), ecology.recoverySeconds(), ecology.deepBudgetPerPlayer(),
                ecology.deepActiveTargetPerPlayer(), ecology.packetIntervalTicks(), ecology.maximizeDirections());
    }

    private static Map<String, EcologyDefinition> loadEcologies() {
        Map<String, EcologyDefinition> result = new LinkedHashMap<>();
        for (String name : List.of("undead", "carrion", "spirits", "end", "sculk")) {
            String path = "/data/depth_director/director_ecologies/" + name + ".json";
            try (var stream = DirectorSimulationTest.class.getResourceAsStream(path)) {
                assertNotNull(stream, path);
                var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                result.put(name, EcologyDefinition.parse(new ResourceLocation("depth_director", name), root));
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot load " + path, exception);
            }
        }
        return Map.copyOf(result);
    }

    private record ProfileCase(String name, EcologyDefinition ecology, int cadenceMinimum,
                               int cadenceMaximum, DirectorPolicy.ProfileSpec spec) {}

    private record Timeline(List<Integer> triggers, List<DirectorPolicy.Phase> phases) {}

    private enum Transport { WALKING, CRAWLER_LABELLED }
}
