package com.bettercontent.depthdirector;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DirectorRuntime {
    static final DirectorRuntime INSTANCE = new DirectorRuntime();
    private static final int SECURE_PROBE_INTERVAL = 100;
    private static final double DISTRESS_HEALTH = 0.35;

    private final RandomSource random = RandomSource.create();
    private final Map<UUID, Encounter> encounters = new LinkedHashMap<>();
    private final Map<UUID, UUID> participantEncounter = new HashMap<>();
    private final Set<UUID> directorMobs = new HashSet<>();
    private int spawnsThisSecond;
    private int roundRobinOffset;

    private DirectorRuntime() {}

    void reset() {
        encounters.clear();
        participantEncounter.clear();
        directorMobs.clear();
        spawnsThisSecond = 0;
        roundRobinOffset = 0;
    }

    void reset(long seed) {
        reset();
        random.setSeed(seed);
    }

    void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (now % 20L == 0L) {
            spawnsThisSecond = 0;
            cleanupMobs(server);
            updatePressure(server, now);
        }
        updateEncounters(server, now);
        processSpawnQueue(server, now);
    }

    void registerMob(Mob mob) {
        if (mob.getPersistentData().getBoolean(SpawnLocator.PROVENANCE_NBT) || mob.getTags().contains(SpawnLocator.PROVENANCE_TAG)) {
            directorMobs.add(mob.getUUID());
        }
    }

    void removeMob(UUID id) { directorMobs.remove(id); }

    void playerDied(MinecraftServer server, UUID player) {
        DirectorSavedData.get(server).reset(player);
        UUID encounterId = participantEncounter.remove(player);
        Encounter encounter = encounterId == null ? null : encounters.get(encounterId);
        if (encounter != null) encounter.participants.remove(player);
    }

    String inspect(ServerPlayer player) {
        DirectorSavedData.Track track = DirectorSavedData.get(player.server).track(player.getUUID());
        UUID encounterId = participantEncounter.get(player.getUUID());
        Encounter encounter = encounterId == null ? null : encounters.get(encounterId);
        double depth = depth(player);
        EcologyRegistry.Blend blend = player.serverLevel().dimension() == Level.OVERWORLD
                ? EcologyRegistry.INSTANCE.blend(player.serverLevel().getSeed(), player.position()) : null;
        return "depth=" + format(depth) + " pressure=" + format(track.pressure())
                + " ecology=" + (blend == null ? "native" : blend.label())
                + " phase=" + (encounter == null ? "build_up" : encounter.phase.name().toLowerCase())
                + " budget=" + (encounter == null ? 0 : encounter.remainingBudget)
                + " active=" + (encounter == null ? 0 : activeNear(player.server, encounter.players(player.server)))
                + " route_failures=" + track.probeFailures();
    }

    boolean force(ServerPlayer player, ResourceLocation ecologyId) {
        if (!eligible(player) || participantEncounter.containsKey(player.getUUID())) return false;
        EcologyDefinition definition = EcologyRegistry.INSTANCE.definitions().get(ecologyId);
        if (definition == null) return false;
        EcologyRegistry.Blend blend = new EcologyRegistry.Blend(definition, null, 0.0);
        createEncounter(player.server, List.of(player), blend, depth(player), player.serverLevel().getGameTime());
        return true;
    }

    private void updatePressure(MinecraftServer server, long now) {
        DirectorSavedData data = DirectorSavedData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (participantEncounter.containsKey(player.getUUID())) continue;
            DirectorSavedData.Track track = data.track(player.getUUID());
            ServerLevel level = player.serverLevel();
            if (!DepthMath.isControlled(player.blockPosition().getY(), controlCeiling(level, player.blockPosition()))) {
                track.pressure(DirectorPolicy.advancePressure(track.pressure(), 0.0, 1.0,
                        false, false, false, false, false, true,
                        DirectorConfig.SURFACE_DECAY_SECONDS.get()));
            }
        }

        for (List<ServerPlayer> group : groups(server.getPlayerList().getPlayers().stream()
                .filter(this::eligible).filter(player -> !participantEncounter.containsKey(player.getUUID())).toList())) {
            ServerLevel level = group.get(0).serverLevel();
            boolean route = true;
            if (now % SECURE_PROBE_INTERVAL == 0L) {
                route = SpawnLocator.hasApproach(level, group, random);
                for (ServerPlayer player : group) {
                    DirectorSavedData.Track track = data.track(player.getUUID());
                    track.probeFailures(DirectorPolicy.routeFailures(track.probeFailures(), true, route));
                }
            }
            boolean secured = group.stream().allMatch(player -> data.track(player.getUUID()).probeFailures() >= 3);
            boolean distressed = healthRatio(group) < DISTRESS_HEALTH;
            boolean downed = group.stream().anyMatch(DownedCompat::isDowned);

            Vec3 center = center(group);
            EcologyRegistry.Blend blend = level.dimension() == Level.OVERWORLD
                    ? EcologyRegistry.INSTANCE.blend(level.getSeed(), center) : null;
            for (ServerPlayer player : group) {
                DirectorSavedData.Track track = data.track(player.getUUID());
                double depth = depth(player);
                int minimum = blend == null ? DirectorPolicy.NATIVE_CADENCE_MIN : (int) Math.round(blend.mix(
                        blend.primary().cadenceMinimumSeconds(), blend.secondary() == null
                                ? blend.primary().cadenceMinimumSeconds() : blend.secondary().cadenceMinimumSeconds()));
                int maximum = blend == null ? DirectorPolicy.NATIVE_CADENCE_MAX : (int) Math.round(blend.mix(
                        blend.primary().cadenceMaximumSeconds(), blend.secondary() == null
                                ? blend.primary().cadenceMaximumSeconds() : blend.secondary().cadenceMaximumSeconds()));
                double cadence = DirectorPolicy.cadenceSeconds(minimum, maximum, track.jitter());
                track.pressure(DirectorPolicy.advancePressure(track.pressure(), depth, cadence,
                        true, secured, distressed, downed, now < track.recoveryUntil(),
                        false, DirectorConfig.SURFACE_DECAY_SECONDS.get()));
            }
            if (!secured && !distressed && !downed
                    && group.stream().anyMatch(player -> data.track(player.getUUID()).pressure() >= 1.0)) {
                createEncounter(server, group, blend, group.stream().mapToDouble(this::depth).average().orElse(0.0), now);
            }
        }
    }

    private void createEncounter(MinecraftServer server, List<ServerPlayer> group, EcologyRegistry.Blend blend,
                                 double depth, long now) {
        DirectorPolicy.Profile profile = profile(blend, depth, random);
        Encounter encounter = new Encounter(UUID.randomUUID(), group.stream().map(ServerPlayer::getUUID).toList(), blend,
                depth, profile, now + profile.warningTicks());
        encounters.put(encounter.id, encounter);
        DirectorSavedData data = DirectorSavedData.get(server);
        for (ServerPlayer player : group) {
            participantEncounter.put(player.getUUID(), encounter.id);
            DirectorSavedData.Track track = data.track(player.getUUID());
            track.pressure(0.0);
            track.probeFailures(0);
            track.rerollJitter(random);
        }
        playWarning(group.get(0).serverLevel(), group, encounter);
    }

    private void updateEncounters(MinecraftServer server, long now) {
        Iterator<Encounter> iterator = encounters.values().iterator();
        while (iterator.hasNext()) {
            Encounter encounter = iterator.next();
            List<ServerPlayer> players = encounter.players(server);
            if (players.isEmpty()) {
                retire(encounter, iterator);
                continue;
            }
            List<ServerPlayer> underground = players.stream().filter(this::eligible).toList();
            if (encounter.phase == DirectorPolicy.Phase.WARNING) {
                if (now % 100L == 0L) playWarning(players.get(0).serverLevel(), players, encounter);
                if (now >= encounter.phaseUntil) {
                    boolean routeOpen = !underground.isEmpty()
                            && SpawnLocator.hasApproach(underground.get(0).serverLevel(), underground, random);
                    encounter.phase = DirectorPolicy.transition(encounter.phase, now, encounter.phaseUntil,
                            !underground.isEmpty(), routeOpen, false, encounter.remainingBudget);
                    if (encounter.phase == DirectorPolicy.Phase.RETIRED) {
                        restoreFrozenPressure(server, encounter);
                        retire(encounter, iterator);
                    } else {
                        encounter.phaseUntil = now + encounter.profile.surgeTicks();
                        encounter.nextPacket = now;
                    }
                }
                continue;
            }
            if (encounter.phase == DirectorPolicy.Phase.SURGE) {
                DirectorPolicy.Phase next = DirectorPolicy.transition(encounter.phase, now, encounter.phaseUntil,
                        !underground.isEmpty(), true, players.stream().anyMatch(DownedCompat::isDowned),
                        encounter.remainingBudget);
                if (next == DirectorPolicy.Phase.RESCUE) {
                    encounter.phase = next;
                    encounter.queuedSpawns = DirectorPolicy.queuedWorkAfterTransition(encounter.queuedSpawns, next);
                    encounter.remainingBudget = 0;
                    continue;
                }
                if (next == DirectorPolicy.Phase.RECOVERY) {
                    beginRecovery(server, encounter, now);
                    continue;
                }
                DirectorPolicy.PopulationLimits limits = DirectorPolicy.scaleForPlayers(encounter.profile,
                        underground.size(), DirectorConfig.GLOBAL_DIRECTOR_CAP.get());
                int currentCap = limits.budget();
                encounter.remainingBudget = Math.min(encounter.remainingBudget,
                        Math.max(0, currentCap - encounter.spentBudget));
                int activeLimit = Math.max(1, limits.activeTarget());
                int active = activeNear(server, underground);
                int interval = DirectorPolicy.packetInterval(encounter.profile.packetIntervalTicks(),
                        healthRatio(underground), DISTRESS_HEALTH);
                if (now >= encounter.nextPacket && active < activeLimit && encounter.queuedSpawns == 0) {
                    int packet = DirectorPolicy.packetSize(active, activeLimit, underground.size());
                    encounter.queuedSpawns = packet;
                    encounter.heavySpawnedInPacket = false;
                    encounter.packetCounts.clear();
                    encounter.nextPacket = now + interval;
                }
                continue;
            }
            if (encounter.phase == DirectorPolicy.Phase.RESCUE) {
                encounter.phase = DirectorPolicy.transition(encounter.phase, now, encounter.phaseUntil,
                        !underground.isEmpty(), true, players.stream().anyMatch(DownedCompat::isDowned), 0);
                if (encounter.phase == DirectorPolicy.Phase.RECOVERY) beginRecovery(server, encounter, now);
                continue;
            }
            if (encounter.phase == DirectorPolicy.Phase.RECOVERY
                    && DirectorPolicy.transition(encounter.phase, now, encounter.phaseUntil,
                    !underground.isEmpty(), true, false, 0) == DirectorPolicy.Phase.RETIRED) {
                retire(encounter, iterator);
            }
        }
    }

    private void processSpawnQueue(MinecraftServer server, long now) {
        int allowance = DirectorPolicy.globalSpawnAllowance(DirectorConfig.MAX_SPAWNS_PER_TICK.get(),
                DirectorConfig.MAX_SPAWNS_PER_SECOND.get(), spawnsThisSecond,
                DirectorConfig.GLOBAL_DIRECTOR_CAP.get(), directorMobs.size());
        if (allowance <= 0 || encounters.isEmpty()) return;
        List<Encounter> active = encounters.values().stream()
                .filter(encounter -> encounter.phase == DirectorPolicy.Phase.SURGE
                        && encounter.queuedSpawns > 0 && encounter.remainingBudget > 0)
                .toList();
        if (active.isEmpty()) return;
        for (int attempt = 0; attempt < allowance; attempt++) {
            Encounter encounter = active.get(DirectorPolicy.roundRobinIndex(roundRobinOffset, attempt, active.size()));
            List<ServerPlayer> players = encounter.players(server).stream().filter(this::eligible).toList();
            if (players.isEmpty() || encounter.queuedSpawns <= 0) continue;
            int sector = encounter.profile.maximizeDirections() ? encounter.nextSector++ & 7 : -1;
            SpawnLocator.SpawnResult result = SpawnLocator.spawn(players.get(0).serverLevel(), players,
                    encounter.blend, encounter.depth, random, sector, !encounter.heavySpawnedInPacket,
                    encounter.remainingBudget, entry -> entry.allowsPacketCount(
                            encounter.packetCounts.getOrDefault(entry.entity(), 0))
                            && entry.allowsEncounterCount(
                            encounter.encounterCounts.getOrDefault(entry.entity(), 0),
                            encounter.participants.size()));
            encounter.queuedSpawns--;
            if (!result.spawned()) continue;
            registerMob(result.mob());
            if (result.role() == EcologyDefinition.Role.HEAVY) encounter.heavySpawnedInPacket = true;
            if (result.entity() != null) {
                encounter.packetCounts.merge(result.entity(), 1, Integer::sum);
                encounter.encounterCounts.merge(result.entity(), 1, Integer::sum);
            }
            encounter.remainingBudget = Math.max(0, encounter.remainingBudget - result.cost());
            encounter.spentBudget += result.cost();
            spawnsThisSecond++;
        }
        roundRobinOffset = DirectorPolicy.nextRoundRobinOffset(roundRobinOffset, active.size());
    }

    private void playWarning(ServerLevel level, List<ServerPlayer> players, Encounter encounter) {
        List<ResourceLocation> sounds = encounter.blend == null ? List.of(new ResourceLocation("minecraft", "entity.zombie.ambient"))
                : encounter.blend.choose(random).warningSounds();
        if (sounds.isEmpty()) return;
        SpawnLocator.approach(level, players, random).ifPresent(position -> {
            SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(sounds.get(random.nextInt(sounds.size())));
            if (sound != null) level.playSound(null, position, sound, SoundSource.HOSTILE, 0.75F, 0.85F + random.nextFloat() * 0.25F);
        });
    }

    private void beginRecovery(MinecraftServer server, Encounter encounter, long now) {
        encounter.phase = DirectorPolicy.Phase.RECOVERY;
        encounter.queuedSpawns = DirectorPolicy.queuedWorkAfterTransition(encounter.queuedSpawns, encounter.phase);
        encounter.phaseUntil = now + encounter.profile.recoveryTicks();
        DirectorSavedData data = DirectorSavedData.get(server);
        encounter.participants.forEach(player -> data.track(player).recoveryUntil(encounter.phaseUntil));
    }

    private void restoreFrozenPressure(MinecraftServer server, Encounter encounter) {
        DirectorSavedData data = DirectorSavedData.get(server);
        encounter.participants.forEach(player -> {
            DirectorSavedData.Track track = data.track(player);
            track.pressure(0.90);
            track.probeFailures(3);
        });
    }

    private void retire(Encounter encounter, Iterator<Encounter> iterator) {
        encounter.participants.forEach(player -> participantEncounter.remove(player, encounter.id));
        iterator.remove();
    }

    private void cleanupMobs(MinecraftServer server) {
        directorMobs.removeIf(id -> findEntity(server, id) == null);
    }

    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) return entity;
        }
        return null;
    }

    private int activeNear(MinecraftServer server, List<ServerPlayer> players) {
        if (players.isEmpty()) return 0;
        int count = 0;
        for (UUID id : directorMobs) {
            Entity entity = findEntity(server, id);
            if (entity == null) continue;
            if (players.stream().anyMatch(player -> player.level() == entity.level() && player.distanceToSqr(entity) <= 96.0 * 96.0)) count++;
        }
        return count;
    }

    private boolean eligible(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        return player.isAlive() && !player.isSpectator() && (mode == GameType.SURVIVAL || mode == GameType.ADVENTURE)
                && player.serverLevel().dimensionType().natural()
                && DepthMath.isControlled(player.blockPosition().getY(),
                controlCeiling(player.serverLevel(), player.blockPosition()));
    }

    private double depth(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        return DepthMath.depthFactor(player.blockPosition().getY(),
                controlCeiling(level, player.blockPosition()), level.getMinBuildHeight());
    }

    static int controlCeiling(ServerLevel level, BlockPos position) {
        return controlCeiling(level, position, DirectorConfig.SURFACE_RESERVE_DEPTH.get());
    }

    static int controlCeiling(ServerLevel level, BlockPos position, int reserveDepth) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                position.getX(), position.getZ());
        return DepthMath.controlCeiling(surfaceY, reserveDepth);
    }

    private static double healthRatio(Collection<ServerPlayer> players) {
        double health = 0.0;
        double maximum = 0.0;
        for (ServerPlayer player : players) {
            if (DownedCompat.isDowned(player)) continue;
            health += Math.max(0.0, player.getHealth());
            maximum += Math.max(1.0, player.getMaxHealth());
        }
        return maximum <= 0.0 ? 0.0 : health / maximum;
    }

    private static Vec3 center(List<ServerPlayer> players) {
        double x = 0.0, y = 0.0, z = 0.0;
        for (ServerPlayer player : players) { x += player.getX(); y += player.getY(); z += player.getZ(); }
        return new Vec3(x / players.size(), y / players.size(), z / players.size());
    }

    static List<List<ServerPlayer>> groups(List<ServerPlayer> players) {
        List<List<ServerPlayer>> result = new ArrayList<>();
        Set<UUID> remaining = new HashSet<>();
        players.forEach(player -> remaining.add(player.getUUID()));
        Map<UUID, ServerPlayer> byId = new HashMap<>();
        players.forEach(player -> byId.put(player.getUUID(), player));
        double radiusSquared = DirectorConfig.GROUP_RADIUS.get() * DirectorConfig.GROUP_RADIUS.get();
        while (!remaining.isEmpty()) {
            UUID seed = remaining.iterator().next();
            remaining.remove(seed);
            List<ServerPlayer> group = new ArrayList<>();
            List<UUID> frontier = new ArrayList<>(List.of(seed));
            while (!frontier.isEmpty()) {
                UUID id = frontier.remove(frontier.size() - 1);
                ServerPlayer player = byId.get(id);
                group.add(player);
                List<UUID> connected = remaining.stream().filter(other -> {
                    ServerPlayer candidate = byId.get(other);
                    return candidate.level() == player.level() && candidate.distanceToSqr(player) <= radiusSquared;
                }).toList();
                connected.forEach(remaining::remove);
                frontier.addAll(connected);
            }
            result.add(group);
        }
        result.sort(Comparator.comparing(group -> group.get(0).getUUID()));
        return result;
    }

    private static String format(double value) { return String.format(java.util.Locale.ROOT, "%.3f", value); }

    private static final class Encounter {
        private final UUID id;
        private final List<UUID> participants;
        private final EcologyRegistry.Blend blend;
        private final double depth;
        private final DirectorPolicy.Profile profile;
        private DirectorPolicy.Phase phase = DirectorPolicy.Phase.WARNING;
        private long phaseUntil;
        private long nextPacket;
        private int remainingBudget;
        private int spentBudget;
        private int queuedSpawns;
        private int nextSector;
        private boolean heavySpawnedInPacket;
        private final Map<ResourceLocation, Integer> packetCounts = new HashMap<>();
        private final Map<ResourceLocation, Integer> encounterCounts = new HashMap<>();

        private Encounter(UUID id, List<UUID> participants, EcologyRegistry.Blend blend, double depth,
                          DirectorPolicy.Profile profile, long phaseUntil) {
            this.id = id;
            this.participants = new ArrayList<>(participants);
            this.blend = blend;
            this.depth = depth;
            this.profile = profile;
            this.phaseUntil = phaseUntil;
            this.remainingBudget = profile.budgetPerPlayer() * participants.size();
        }

        private List<ServerPlayer> players(MinecraftServer server) {
            return participants.stream().map(server.getPlayerList()::getPlayer).filter(java.util.Objects::nonNull).toList();
        }
    }

    private static DirectorPolicy.Profile profile(EcologyRegistry.Blend blend, double depth, RandomSource random) {
        DirectorPolicy.ProfileSpec spec = DirectorPolicy.nativeSpec();
        if (blend != null) {
            EcologyDefinition primary = blend.primary();
            EcologyDefinition secondary = blend.secondary() == null ? primary : blend.secondary();
            spec = new DirectorPolicy.ProfileSpec(
                    (int) Math.round(blend.mix(primary.warningMinimumSeconds(), secondary.warningMinimumSeconds())),
                    (int) Math.round(blend.mix(primary.warningMaximumSeconds(), secondary.warningMaximumSeconds())),
                    (int) Math.round(blend.mix(primary.surgeSeconds(), secondary.surgeSeconds())),
                    (int) Math.round(blend.mix(primary.recoverySeconds(), secondary.recoverySeconds())),
                    (int) Math.round(blend.mix(primary.deepBudgetPerPlayer(), secondary.deepBudgetPerPlayer())),
                    (int) Math.round(blend.mix(primary.deepActiveTargetPerPlayer(), secondary.deepActiveTargetPerPlayer())),
                    (int) Math.round(blend.mix(primary.packetIntervalTicks(), secondary.packetIntervalTicks())),
                    primary.maximizeDirections() || secondary.maximizeDirections());
        }
        return DirectorPolicy.scaleProfile(spec, depth, random.nextDouble());
    }
}
