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
    private static final int NATIVE_CADENCE_MIN = 240;
    private static final int NATIVE_CADENCE_MAX = 420;
    private static final int NATIVE_WARNING_MIN = 10;
    private static final int NATIVE_WARNING_MAX = 24;
    private static final int NATIVE_SURGE = 90;
    private static final int NATIVE_RECOVERY = 90;
    private static final int NATIVE_BUDGET = 64;
    private static final int NATIVE_ACTIVE = 36;
    private static final int NATIVE_PACKET_INTERVAL = 100;

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

    void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (now % 20L == 0L) {
            spawnsThisSecond = 0;
            cleanupMobs(server);
            updatePressure(server, now);
            updateEncounters(server, now);
        }
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
            if (player.getY() >= level.getSeaLevel()) {
                double decay = 1.0 / Math.max(1, DirectorConfig.SURFACE_DECAY_SECONDS.get());
                track.pressure(track.pressure() - decay);
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
                    track.probeFailures(route ? 0 : track.probeFailures() + 1);
                }
            }
            boolean secured = group.stream().allMatch(player -> data.track(player.getUUID()).probeFailures() >= 3);
            if (secured || healthRatio(group) < DISTRESS_HEALTH || group.stream().anyMatch(DownedCompat::isDowned)) continue;

            Vec3 center = center(group);
            EcologyRegistry.Blend blend = level.dimension() == Level.OVERWORLD
                    ? EcologyRegistry.INSTANCE.blend(level.getSeed(), center) : null;
            for (ServerPlayer player : group) {
                DirectorSavedData.Track track = data.track(player.getUUID());
                if (now < track.recoveryUntil()) continue;
                double depth = depth(player);
                int minimum = blend == null ? NATIVE_CADENCE_MIN : (int) Math.round(blend.mix(
                        blend.primary().cadenceMinimumSeconds(), blend.secondary() == null
                                ? blend.primary().cadenceMinimumSeconds() : blend.secondary().cadenceMinimumSeconds()));
                int maximum = blend == null ? NATIVE_CADENCE_MAX : (int) Math.round(blend.mix(
                        blend.primary().cadenceMaximumSeconds(), blend.secondary() == null
                                ? blend.primary().cadenceMaximumSeconds() : blend.secondary().cadenceMaximumSeconds()));
                double cadence = DepthMath.lerp(minimum, maximum, track.jitter());
                track.pressure(track.pressure() + depth / Math.max(1.0, cadence));
            }
            if (group.stream().anyMatch(player -> data.track(player.getUUID()).pressure() >= 1.0)) {
                createEncounter(server, group, blend, group.stream().mapToDouble(this::depth).average().orElse(0.0), now);
            }
        }
    }

    private void createEncounter(MinecraftServer server, List<ServerPlayer> group, EcologyRegistry.Blend blend,
                                 double depth, long now) {
        Profile profile = Profile.from(blend, depth, random);
        Encounter encounter = new Encounter(UUID.randomUUID(), group.stream().map(ServerPlayer::getUUID).toList(), blend,
                depth, profile, now + profile.warningTicks);
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
            if (encounter.phase == Phase.WARNING) {
                if (now % 100L == 0L) playWarning(players.get(0).serverLevel(), players, encounter);
                if (now >= encounter.phaseUntil) {
                    if (underground.isEmpty() || !SpawnLocator.hasApproach(underground.get(0).serverLevel(), underground, random)) {
                        restoreFrozenPressure(server, encounter);
                        retire(encounter, iterator);
                    } else {
                        encounter.phase = Phase.SURGE;
                        encounter.phaseUntil = now + encounter.profile.surgeTicks;
                        encounter.nextPacket = now;
                    }
                }
                continue;
            }
            if (encounter.phase == Phase.SURGE) {
                if (players.stream().anyMatch(DownedCompat::isDowned)) {
                    encounter.phase = Phase.RESCUE;
                    encounter.queuedSpawns = 0;
                    encounter.remainingBudget = 0;
                    continue;
                }
                if (underground.isEmpty() || now >= encounter.phaseUntil || encounter.remainingBudget <= 0) {
                    beginRecovery(server, encounter, now);
                    continue;
                }
                int currentCap = encounter.profile.deepBudgetPerPlayer * underground.size();
                encounter.remainingBudget = Math.min(encounter.remainingBudget,
                        Math.max(0, currentCap - encounter.spentBudget));
                int activeLimit = Math.max(1, encounter.profile.activeTargetPerPlayer * underground.size());
                int active = activeNear(server, underground);
                int interval = encounter.profile.packetIntervalTicks;
                if (healthRatio(underground) < DISTRESS_HEALTH) interval *= 3;
                if (now >= encounter.nextPacket && active < activeLimit) {
                    int packet = Math.min(activeLimit - active, Math.max(1, 6 * underground.size()));
                    encounter.queuedSpawns = Math.min(encounter.queuedSpawns + packet, packet * 2);
                    encounter.heavySpawnedInPacket = false;
                    encounter.nextPacket = now + interval;
                }
                continue;
            }
            if (encounter.phase == Phase.RESCUE) {
                if (players.stream().noneMatch(DownedCompat::isDowned)) beginRecovery(server, encounter, now);
                continue;
            }
            if (encounter.phase == Phase.RECOVERY && now >= encounter.phaseUntil) retire(encounter, iterator);
        }
    }

    private void processSpawnQueue(MinecraftServer server, long now) {
        int tickLimit = DirectorConfig.MAX_SPAWNS_PER_TICK.get();
        int secondRemaining = Math.max(0, DirectorConfig.MAX_SPAWNS_PER_SECOND.get() - spawnsThisSecond);
        int globalRemaining = Math.max(0, DirectorConfig.GLOBAL_DIRECTOR_CAP.get() - directorMobs.size());
        int allowance = Math.min(tickLimit, Math.min(secondRemaining, globalRemaining));
        if (allowance <= 0 || encounters.isEmpty()) return;
        List<Encounter> active = encounters.values().stream()
                .filter(encounter -> encounter.phase == Phase.SURGE && encounter.queuedSpawns > 0 && encounter.remainingBudget > 0)
                .toList();
        if (active.isEmpty()) return;
        for (int attempt = 0; attempt < allowance; attempt++) {
            Encounter encounter = active.get((roundRobinOffset + attempt) % active.size());
            List<ServerPlayer> players = encounter.players(server).stream().filter(this::eligible).toList();
            if (players.isEmpty() || encounter.queuedSpawns <= 0) continue;
            int sector = encounter.profile.maximizeDirections ? encounter.nextSector++ & 7 : -1;
            SpawnLocator.SpawnResult result = SpawnLocator.spawn(players.get(0).serverLevel(), players,
                    encounter.blend, encounter.depth, random, sector, !encounter.heavySpawnedInPacket);
            encounter.queuedSpawns--;
            if (!result.spawned()) continue;
            registerMob(result.mob());
            if (result.role() == EcologyDefinition.Role.HEAVY) encounter.heavySpawnedInPacket = true;
            encounter.remainingBudget = Math.max(0, encounter.remainingBudget - result.cost());
            encounter.spentBudget += result.cost();
            spawnsThisSecond++;
        }
        roundRobinOffset = (roundRobinOffset + 1) % Math.max(1, active.size());
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
        encounter.phase = Phase.RECOVERY;
        encounter.queuedSpawns = 0;
        encounter.phaseUntil = now + encounter.profile.recoveryTicks;
        DirectorSavedData data = DirectorSavedData.get(server);
        encounter.participants.forEach(player -> data.track(player).recoveryUntil(encounter.phaseUntil));
    }

    private void restoreFrozenPressure(MinecraftServer server, Encounter encounter) {
        DirectorSavedData data = DirectorSavedData.get(server);
        encounter.participants.forEach(player -> {
            DirectorSavedData.Track track = data.track(player);
            track.pressure(1.0);
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
                && player.getY() < player.serverLevel().getSeaLevel();
    }

    private double depth(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        return DepthMath.depthFactor(player.blockPosition().getY(), level.getSeaLevel(), level.getMinBuildHeight());
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

    private enum Phase { WARNING, SURGE, RESCUE, RECOVERY }

    private static final class Encounter {
        private final UUID id;
        private final List<UUID> participants;
        private final EcologyRegistry.Blend blend;
        private final double depth;
        private final Profile profile;
        private Phase phase = Phase.WARNING;
        private long phaseUntil;
        private long nextPacket;
        private int remainingBudget;
        private int spentBudget;
        private int queuedSpawns;
        private int nextSector;
        private boolean heavySpawnedInPacket;

        private Encounter(UUID id, List<UUID> participants, EcologyRegistry.Blend blend, double depth,
                          Profile profile, long phaseUntil) {
            this.id = id;
            this.participants = new ArrayList<>(participants);
            this.blend = blend;
            this.depth = depth;
            this.profile = profile;
            this.phaseUntil = phaseUntil;
            this.remainingBudget = profile.deepBudgetPerPlayer * participants.size();
        }

        private List<ServerPlayer> players(MinecraftServer server) {
            return participants.stream().map(server.getPlayerList()::getPlayer).filter(java.util.Objects::nonNull).toList();
        }
    }

    private record Profile(int warningTicks, int surgeTicks, int recoveryTicks, int deepBudgetPerPlayer,
                           int activeTargetPerPlayer, int packetIntervalTicks, boolean maximizeDirections) {
        private static Profile from(EcologyRegistry.Blend blend, double depth, RandomSource random) {
            int warningMin = NATIVE_WARNING_MIN, warningMax = NATIVE_WARNING_MAX;
            int surge = NATIVE_SURGE, recovery = NATIVE_RECOVERY, budget = NATIVE_BUDGET,
                    active = NATIVE_ACTIVE, packet = NATIVE_PACKET_INTERVAL;
            boolean directions = false;
            if (blend != null) {
                EcologyDefinition primary = blend.primary();
                EcologyDefinition secondary = blend.secondary() == null ? primary : blend.secondary();
                warningMin = (int) Math.round(blend.mix(primary.warningMinimumSeconds(), secondary.warningMinimumSeconds()));
                warningMax = (int) Math.round(blend.mix(primary.warningMaximumSeconds(), secondary.warningMaximumSeconds()));
                surge = (int) Math.round(blend.mix(primary.surgeSeconds(), secondary.surgeSeconds()));
                recovery = (int) Math.round(blend.mix(primary.recoverySeconds(), secondary.recoverySeconds()));
                budget = (int) Math.round(blend.mix(primary.deepBudgetPerPlayer(), secondary.deepBudgetPerPlayer()));
                active = (int) Math.round(blend.mix(primary.deepActiveTargetPerPlayer(), secondary.deepActiveTargetPerPlayer()));
                packet = (int) Math.round(blend.mix(primary.packetIntervalTicks(), secondary.packetIntervalTicks()));
                directions = primary.maximizeDirections() || secondary.maximizeDirections();
            }
            int warning = warningMin + random.nextInt(Math.max(1, warningMax - warningMin + 1));
            int scaledBudget = (int) Math.round(DepthMath.lerp(8, budget, depth));
            int scaledActive = (int) Math.round(DepthMath.lerp(6, active, depth));
            return new Profile(warning * 20, surge * 20, recovery * 20, scaledBudget, scaledActive, packet, directions);
        }
    }
}
