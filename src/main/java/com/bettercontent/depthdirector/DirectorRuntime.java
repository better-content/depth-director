package com.bettercontent.depthdirector;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.Blocks;
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
import java.util.function.Predicate;

final class DirectorRuntime {
    static final DirectorRuntime INSTANCE = new DirectorRuntime();
    private static final int SECURE_PROBE_INTERVAL = 100;
    private static final double DISTRESS_HEALTH = 0.35;

    private final RandomSource random = RandomSource.create();
    private final Map<UUID, Encounter> encounters = new LinkedHashMap<>();
    private final Map<UUID, UUID> participantEncounter = new HashMap<>();
    private final Set<UUID> directorMobs = new HashSet<>();
    private final Map<UUID, UUID> mobEncounter = new HashMap<>();
    private int spawnsThisSecond;
    private int roundRobinOffset;
    private boolean restored;

    private DirectorRuntime() {}

    void reset() {
        encounters.clear();
        participantEncounter.clear();
        directorMobs.clear();
        mobEncounter.clear();
        spawnsThisSecond = 0;
        roundRobinOffset = 0;
        restored = false;
    }

    void reset(long seed) {
        reset();
        random.setSeed(seed);
    }

    void tick(MinecraftServer server) {
        if (!restored) { restore(server); restored = true; }
        long now = server.overworld().getGameTime();
        if (now % 20L == 0L) {
            spawnsThisSecond = 0;
            cleanupMobs(server);
            updateInjuryRelief(server);
            updatePressure(server, now);
        }
        updateEncounters(server, now);
        processSpawnQueue(server, now);
        // Encounter state is authoritative in SavedData, so normal autosaves also
        // capture phase, ownership, and budget mutations between clean shutdowns.
        if (now % 20L == 0L) persist(server);
    }

    void persist(MinecraftServer server) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        encounters.values().forEach(e -> {
            CompoundTag tag = new CompoundTag(); tag.putUUID("Id", e.id); tag.putUUID("Family", e.family);
            ListTag people = new ListTag(); e.participants.forEach(id -> { CompoundTag p = new CompoundTag(); p.putUUID("Id", id); people.add(p); }); tag.put("Participants", people);
            if (e.pursuitTarget != null) tag.putUUID("Target", e.pursuitTarget);
            tag.putDouble("Depth", e.depth); tag.putString("Phase", e.phase.name()); tag.putLong("PhaseUntil", e.phaseUntil);
            tag.putLong("LastPacket", e.lastPacketAt); tag.putInt("Remaining", e.remainingBudget); tag.putInt("Spent", e.spentBudget);
            tag.putInt("NextSector", e.nextSector); tag.putString("SuspendedPhase", e.suspendedPhase.name()); tag.putLong("SuspendedTicks", e.suspendedTicks);
            tag.putString("Suspension", e.suspensionReason); tag.putString("Failure", e.lastFailure);
            tag.putInt("Warning", e.profile.warningTicks()); tag.putInt("Surge", e.profile.surgeTicks()); tag.putInt("Recovery", e.profile.recoveryTicks());
            tag.putInt("Budget", e.profile.budgetPerPlayer()); tag.putInt("Active", e.profile.activeTargetPerPlayer()); tag.putInt("Interval", e.profile.packetIntervalTicks()); tag.putBoolean("Directions", e.profile.maximizeDirections());
            tag.putInt("Queued", e.queuedSpawns); tag.putInt("PacketSector", e.packetTelegraphSector);
            tag.putInt("PacketStateVersion", 1);
            tag.putLong("PacketTelegraphUntil", e.packetTelegraphUntil);
            tag.putBoolean("PacketContinuation", e.packetTelegraphContinuation);
            tag.putBoolean("HeavySpawned", e.heavySpawnedInPacket);
            tag.putBoolean("HasPacketPosition", e.packetTelegraphPosition != null);
            if (e.packetTelegraphPosition != null) {
                tag.putInt("PacketX", e.packetTelegraphPosition.getX());
                tag.putInt("PacketY", e.packetTelegraphPosition.getY());
                tag.putInt("PacketZ", e.packetTelegraphPosition.getZ());
            }
            CompoundTag packetCounts = new CompoundTag();
            e.packetCounts.forEach((id, count) -> packetCounts.putInt(id.toString(), count));
            tag.put("PacketCounts", packetCounts);
            if (e.blend != null) {
                tag.putString("BlendPrimary", e.blend.primary().id().toString());
                if (e.blend.secondary() != null) tag.putString("BlendSecondary", e.blend.secondary().id().toString());
                tag.putDouble("BlendWeight", e.blend.secondaryWeight());
            }
            CompoundTag counts = new CompoundTag(); e.encounterCounts.forEach((id, count) -> counts.putInt(id.toString(), count)); tag.put("Counts", counts);
            list.add(tag);
        }); root.put("List", list); DirectorSavedData.get(server).encounters(root);
        CompoundTag mobs = new CompoundTag(); mobEncounter.forEach((mob, encounter) -> mobs.putUUID(mob.toString(), encounter)); root.put("Mobs", mobs);
        DirectorSavedData.get(server).encounters(root);
    }

    private void restore(MinecraftServer server) {
        ListTag list = DirectorSavedData.get(server).encounters().getList("List", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i); List<UUID> people = new ArrayList<>();
            ListTag p = t.getList("Participants", Tag.TAG_COMPOUND); for (int j = 0; j < p.size(); j++) if (p.getCompound(j).hasUUID("Id")) people.add(p.getCompound(j).getUUID("Id"));
            if (people.isEmpty() || !t.hasUUID("Id")) continue;
            DirectorPolicy.Profile profile = new DirectorPolicy.Profile(t.getInt("Warning"), t.getInt("Surge"), t.getInt("Recovery"), t.getInt("Budget"), t.getInt("Active"), t.getInt("Interval"), t.getBoolean("Directions"));
            EcologyRegistry.Blend blend = restoredBlend(t);
            Encounter e = new Encounter(t.getUUID("Id"), t.hasUUID("Family") ? t.getUUID("Family") : t.getUUID("Id"), people, blend, t.getDouble("Depth"), profile, t.getLong("PhaseUntil"), t.getInt("Remaining"), t.getInt("Spent"));
            try { e.phase = DirectorPolicy.Phase.valueOf(t.getString("Phase")); } catch (IllegalArgumentException ignored) { continue; }
            e.lastPacketAt = t.getLong("LastPacket"); e.nextSector = t.getInt("NextSector"); e.suspendedTicks = t.getLong("SuspendedTicks"); e.suspensionReason = t.getString("Suspension"); e.lastFailure = t.getString("Failure");
            CompoundTag counts = t.getCompound("Counts"); for (String key : counts.getAllKeys()) try { e.encounterCounts.put(new ResourceLocation(key), counts.getInt(key)); } catch (RuntimeException ignored) { }
            CompoundTag packetCounts = t.getCompound("PacketCounts");
            Map<ResourceLocation, Integer> restoredPacketCounts = new HashMap<>();
            for (String key : packetCounts.getAllKeys()) try {
                restoredPacketCounts.put(new ResourceLocation(key), packetCounts.getInt(key));
            } catch (RuntimeException ignored) { }
            boolean hasPacketCoordinates = t.getBoolean("HasPacketPosition")
                    && t.contains("PacketX", Tag.TAG_INT) && t.contains("PacketY", Tag.TAG_INT)
                    && t.contains("PacketZ", Tag.TAG_INT);
            BlockPos packetPosition = hasPacketCoordinates
                    ? new BlockPos(t.getInt("PacketX"), t.getInt("PacketY"), t.getInt("PacketZ")) : null;
            boolean packetStateComplete = t.contains("PacketStateVersion", Tag.TAG_INT)
                    && t.contains("PacketTelegraphUntil", Tag.TAG_LONG)
                    && t.contains("PacketContinuation", Tag.TAG_BYTE)
                    && t.contains("HeavySpawned", Tag.TAG_BYTE)
                    && t.contains("PacketSector", Tag.TAG_INT)
                    && t.contains("HasPacketPosition", Tag.TAG_BYTE)
                    && t.contains("PacketCounts", Tag.TAG_COMPOUND)
                    && (!t.getBoolean("HasPacketPosition") || hasPacketCoordinates);
            EncounterPersistencePolicy.PacketState packet = EncounterPersistencePolicy.restorePacket(
                    packetStateComplete ? t.getInt("PacketStateVersion") : 0,
                    t.getLong("PacketTelegraphUntil"), packetPosition,
                    t.getInt("PacketSector"),
                    t.getBoolean("PacketContinuation"), t.getInt("Queued"), t.getBoolean("HeavySpawned"),
                    restoredPacketCounts, t.contains("PacketCounts", Tag.TAG_COMPOUND), e.encounterCounts);
            e.packetTelegraphUntil = packet.telegraphUntil();
            e.packetTelegraphPosition = packet.position();
            e.packetTelegraphSector = packet.sector();
            e.packetTelegraphContinuation = packet.continuation();
            e.queuedSpawns = packet.queued();
            e.heavySpawnedInPacket = packet.heavySpawned();
            e.packetCounts.putAll(packet.packetCounts());
            try { e.suspendedPhase = DirectorPolicy.Phase.valueOf(t.getString("SuspendedPhase")); } catch (IllegalArgumentException ignored) { }
            if (t.hasUUID("Target")) e.pursuitTarget = t.getUUID("Target"); encounters.put(e.id, e); e.participants.forEach(id -> participantEncounter.put(id, e.id));
        }
        CompoundTag mobs = DirectorSavedData.get(server).encounters().getCompound("Mobs"); for (String key : mobs.getAllKeys()) try { UUID mob = UUID.fromString(key); directorMobs.add(mob); mobEncounter.put(mob, mobs.getUUID(key)); } catch (RuntimeException ignored) { }
    }

    private static EcologyRegistry.Blend restoredBlend(CompoundTag tag) {
        if (!tag.contains("BlendPrimary")) return null;
        try {
            EcologyDefinition primary = EcologyRegistry.INSTANCE.definitions().get(new ResourceLocation(tag.getString("BlendPrimary")));
            EcologyDefinition secondary = tag.contains("BlendSecondary") ? EcologyRegistry.INSTANCE.definitions().get(new ResourceLocation(tag.getString("BlendSecondary"))) : null;
            return primary == null ? null : new EcologyRegistry.Blend(primary, secondary, tag.getDouble("BlendWeight"));
        } catch (RuntimeException ignored) { return null; }
    }

    void registerMob(Mob mob) {
        if (SpawnLocator.isDirectorMob(mob)) {
            directorMobs.add(mob.getUUID());
            SpawnLocator.restoreDirectorMob(mob);
        }
    }

    void removeMob(UUID id) { directorMobs.remove(id); mobEncounter.remove(id); }

    boolean isParticipant(ServerPlayer player) {
        return player != null && participantEncounter.containsKey(player.getUUID())
                && encounters.containsKey(participantEncounter.get(player.getUUID()));
    }

    void playerDied(MinecraftServer server, UUID player) {
        DirectorSavedData.get(server).reset(player);
        UUID encounterId = participantEncounter.remove(player);
        Encounter encounter = encounterId == null ? null : encounters.get(encounterId);
        if (encounter != null) {
            encounter.participants.remove(player);
            encounter.replacePursuitTarget();
        }
    }

    String inspect(ServerPlayer player) {
        DirectorSavedData saved = DirectorSavedData.peek(player.server);
        DirectorSavedData.Track track = saved == null ? null : saved.peekTrack(player.getUUID());
        UUID encounterId = participantEncounter.get(player.getUUID());
        Encounter encounter = encounterId == null ? null : encounters.get(encounterId);
        double depth = depth(player);
        EcologyRegistry.Blend blend = player.serverLevel().dimension() == Level.OVERWORLD
                ? EcologyRegistry.INSTANCE.blend(player.serverLevel().getSeed(), player.position()) : null;
        String locality = player.serverLevel().dimension().location() + "@" + player.blockPosition().toShortString();
        String target = "none";
        if (encounter != null && encounter.pursuitTarget != null) {
            ServerPlayer pursued = player.server.getPlayerList().getPlayer(encounter.pursuitTarget);
            target = pursued == null ? "offline:" + encounter.pursuitTarget
                    : pursued.getGameProfile().getName() + "@"
                    + pursued.serverLevel().dimension().location() + ":" + pursued.blockPosition().toShortString();
        }
        String approach = encounter == null || encounter.packetTelegraphPosition == null ? "none"
                : encounter.packetTelegraphPosition.toShortString() + "/sector=" + encounter.packetTelegraphSector;
        return "player=" + player.getGameProfile().getName() + " locality=" + locality
                + " depth=" + format(depth) + " pressure=" + format(track == null ? 0.0 : track.pressure())
                + " ecology=" + (blend == null ? "native" : blend.label())
                + " encounter=" + (encounter == null ? "none" : encounter.id)
                + " family=" + (encounter == null ? "none" : encounter.family)
                + " phase=" + (encounter == null ? "build_up" : encounter.phase.name().toLowerCase())
                + " target=" + target + " participants=" + (encounter == null ? 0 : encounter.participants.size())
                + " warned_approach=" + approach
                + " spent=" + (encounter == null ? 0 : encounter.spentBudget)
                + " remaining=" + (encounter == null ? 0 : encounter.remainingBudget)
                + " active=" + (encounter == null ? 0 : activeNear(player.server, encounter.players(player.server)))
                + " suspension=" + (encounter == null || encounter.phase != DirectorPolicy.Phase.SUSPENDED
                    ? "none" : encounter.suspensionReason)
                + " last_failure=" + (encounter == null ? "none" : encounter.lastFailure)
                + " route_failures=" + (track == null ? 0 : track.probeFailures());
    }

    private void updatePressure(MinecraftServer server, long now) {
        DirectorSavedData data = DirectorSavedData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (participantEncounter.containsKey(player.getUUID())) continue;
            DirectorSavedData.Track track = data.track(player.getUUID());
            ServerLevel level = player.serverLevel();
            if (!DepthMath.isControlled(player.blockPosition().getY(), controlCeiling(level, player.blockPosition()))) {
                track.pressure(DirectorPolicy.advancePressure(track.pressure(), 0.0, 1.0,
                        false, false, false, 0.0, false, true,
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
            double averageMaims = averageInjuryRelief(group, data);

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
                        true, secured, distressed, averageMaims, now < track.recoveryUntil(),
                        false, DirectorConfig.SURFACE_DECAY_SECONDS.get()));
            }
            if (!secured && !distressed
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
        List<ServerPlayer> pursuit = encounter.pursuitPlayers(server, this::eligible);
        if (!pursuit.isEmpty()) playWarning(pursuit.get(0).serverLevel(), pursuit, encounter);
    }

    private void updateEncounters(MinecraftServer server, long now) {
        mergeRejoinedEncounters(server);
        List<Encounter> localityChildren = new ArrayList<>();
        Iterator<Encounter> iterator = encounters.values().iterator();
        while (iterator.hasNext()) {
            Encounter encounter = iterator.next();
            List<ServerPlayer> players = encounter.players(server);
            if (players.isEmpty()) {
                suspend(encounter, now, "participants_offline");
                continue;
            }
            if (splitSeparatedEncounter(server, encounter, players, now, localityChildren)) {
                iterator.remove();
                continue;
            }
            if (encounter.phase == DirectorPolicy.Phase.SUSPENDED) {
                resumeIfPursuitAvailable(server, encounter, now);
                continue;
            }
            List<ServerPlayer> underground = encounter.pursuitPlayers(server, this::eligible);
            if (underground.isEmpty() && encounter.phase != DirectorPolicy.Phase.RECOVERY) {
                suspend(encounter, now, "no_eligible_pursuit");
                continue;
            }
            if (encounter.phase == DirectorPolicy.Phase.WARNING) {
                if (now % 100L == 0L) playWarning(underground.get(0).serverLevel(), underground, encounter);
                if (now >= encounter.phaseUntil) {
                    boolean routeOpen = !underground.isEmpty()
                            && SpawnLocator.hasApproach(underground.get(0).serverLevel(), underground, random);
                    DirectorPolicy.Phase next = DirectorPolicy.transition(encounter.phase, now, encounter.phaseUntil,
                            !underground.isEmpty(), routeOpen, encounter.remainingBudget);
                    if (next == DirectorPolicy.Phase.SUSPENDED) {
                        suspend(encounter, now, "route_unavailable");
                    } else if (next == DirectorPolicy.Phase.RETIRED) {
                        encounter.phase = next;
                        restoreFrozenPressure(server, encounter);
                        retire(encounter, iterator);
                    } else {
                        encounter.phase = next;
                        encounter.phaseUntil = now + encounter.profile.surgeTicks();
                        encounter.lastPacketAt = -1L;
                    }
                }
                continue;
            }
            if (encounter.phase == DirectorPolicy.Phase.SURGE) {
                DirectorPolicy.Phase next = DirectorPolicy.transition(encounter.phase, now, encounter.phaseUntil,
                        !underground.isEmpty(), true,
                        encounter.remainingBudget);
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
                        healthRatio(underground), DISTRESS_HEALTH,
                        averageInjuryRelief(underground, DirectorSavedData.get(server)));
                if (encounter.packetTelegraphUntil >= 0L) {
                    playPacketTelegraph(underground.get(0).serverLevel(), underground, encounter, now);
                    if (DirectorPolicy.packetTelegraphComplete(now, encounter.packetTelegraphUntil)) {
                        if (!encounter.packetTelegraphContinuation) {
                            encounter.queuedSpawns = DirectorPolicy.packetSize(active, activeLimit, underground.size());
                            encounter.heavySpawnedInPacket = false;
                            encounter.packetCounts.clear();
                            encounter.lastPacketAt = now;
                        }
                        completePacketTelegraph(encounter);
                    }
                } else if ((encounter.lastPacketAt < 0L || now - encounter.lastPacketAt >= interval)
                        && active < activeLimit && encounter.queuedSpawns == 0) {
                    if (!beginPacketTelegraph(underground.get(0).serverLevel(), underground, encounter, now, false)) {
                        suspend(encounter, now, "warned_approach_unavailable");
                    }
                }
                continue;
            }
            if (encounter.phase == DirectorPolicy.Phase.RECOVERY
                    && DirectorPolicy.transition(encounter.phase, now, encounter.phaseUntil,
                    !underground.isEmpty(), true, 0) == DirectorPolicy.Phase.RETIRED) {
                retire(encounter, iterator);
            }
        }
        localityChildren.forEach(child -> encounters.put(child.id, child));
    }

    private boolean splitSeparatedEncounter(MinecraftServer server, Encounter encounter, List<ServerPlayer> players,
                                            long now, List<Encounter> children) {
        if (players.size() != encounter.participants.size()) {
            encounter.localitySeparatedSince = -1L;
            return false;
        }
        List<List<ServerPlayer>> localGroups = groups(players);
        if (localGroups.size() <= 1) {
            encounter.localitySeparatedSince = -1L;
            return false;
        }
        if (encounter.localitySeparatedSince < 0L) {
            encounter.localitySeparatedSince = now;
            return false;
        }
        if (now - encounter.localitySeparatedSince < DirectorConfig.LOCALITY_GRACE_SECONDS.get() * 20L) return false;

        List<List<UUID>> participants = localGroups.stream()
                .map(group -> group.stream().map(ServerPlayer::getUUID).sorted().toList()).toList();
        List<Integer> weights = participants.stream().map(List::size).toList();
        int[] remaining = DirectorPolicy.allocateConserved(encounter.remainingBudget, weights);
        int[] spent = DirectorPolicy.allocateConserved(encounter.spentBudget, weights);
        for (int index = 0; index < participants.size(); index++) {
            Encounter child = Encounter.split(encounter, participants.get(index), remaining[index], spent[index]);
            children.add(child);
            child.participants.forEach(player -> participantEncounter.put(player, child.id));
        }
        return true;
    }

    private void mergeRejoinedEncounters(MinecraftServer server) {
        Map<UUID, List<Encounter>> byFamily = new HashMap<>();
        encounters.values().forEach(encounter -> byFamily.computeIfAbsent(encounter.family, ignored -> new ArrayList<>()).add(encounter));
        for (List<Encounter> family : byFamily.values()) {
            if (family.size() < 2) continue;
            List<ServerPlayer> players = family.stream().flatMap(encounter -> encounter.players(server).stream()).toList();
            int participantCount = family.stream().mapToInt(encounter -> encounter.participants.size()).sum();
            if (players.size() != participantCount || groups(players).size() != 1) continue;
            Encounter merged = Encounter.merge(family);
            family.forEach(encounter -> encounters.remove(encounter.id));
            encounters.put(merged.id, merged);
            merged.participants.forEach(player -> participantEncounter.put(player, merged.id));
        }
    }

    private void processSpawnQueue(MinecraftServer server, long now) {
        int allowance = DirectorPolicy.globalSpawnAllowance(DirectorConfig.MAX_SPAWNS_PER_TICK.get(),
                DirectorConfig.MAX_SPAWNS_PER_SECOND.get(), spawnsThisSecond,
                DirectorConfig.GLOBAL_DIRECTOR_CAP.get(), directorMobs.size());
        if (allowance <= 0 || encounters.isEmpty()) return;
        List<Encounter> active = encounters.values().stream()
                .filter(encounter -> encounter.phase == DirectorPolicy.Phase.SURGE
                        && encounter.packetTelegraphUntil < 0L
                        && encounter.queuedSpawns > 0 && encounter.remainingBudget > 0)
                .toList();
        if (active.isEmpty()) return;
        for (int attempt = 0; attempt < allowance; attempt++) {
            Encounter encounter = active.get(DirectorPolicy.roundRobinIndex(roundRobinOffset, attempt, active.size()));
            List<ServerPlayer> players = encounter.pursuitPlayers(server, this::eligible);
            if (players.isEmpty() || encounter.queuedSpawns <= 0 || encounter.packetTelegraphUntil >= 0L) continue;
            BlockPos warnedApproach = encounter.packetTelegraphPosition;
            int warnedSector = encounter.packetTelegraphSector;
            if (warnedApproach == null) {
                encounter.queuedSpawns = 0;
                continue;
            }
            SpawnLocator.SpawnResult result = SpawnLocator.spawnAt(players.get(0).serverLevel(), players,
                    encounter.blend, encounter.depth, random, warnedApproach, !encounter.heavySpawnedInPacket,
                    encounter.remainingBudget, entry -> entry.allowsPacketCount(
                            encounter.packetCounts.getOrDefault(entry.entity(), 0))
                            && entry.allowsEncounterCount(
                            encounter.encounterCounts.getOrDefault(entry.entity(), 0),
                            encounter.participants.size()));
            encounter.queuedSpawns--;
            if (!result.spawned()) {
                // The warned corridor did not admit this selected mob. Cancel before direction changes.
                encounter.lastFailure = "warned_approach_rejected";
                encounter.queuedSpawns = 0;
                cancelPacketTelegraph(encounter);
                continue;
            }
            encounter.nextSector = DirectorPolicy.nextSectorAfterSpawn(warnedSector,
                    encounter.profile.maximizeDirections(), true);
            registerMob(result.mob());
            mobEncounter.put(result.mob().getUUID(), encounter.id);
            if (result.role() == EcologyDefinition.Role.HEAVY) encounter.heavySpawnedInPacket = true;
            if (result.entity() != null) {
                encounter.packetCounts.merge(result.entity(), 1, Integer::sum);
                encounter.encounterCounts.merge(result.entity(), 1, Integer::sum);
            }
            encounter.remainingBudget = Math.max(0, encounter.remainingBudget - result.cost());
            encounter.spentBudget += result.cost();
            encounter.lastFailure = "none";
            spawnsThisSecond++;
            if (encounter.queuedSpawns > 0) {
                if (!beginPacketTelegraph(players.get(0).serverLevel(), players, encounter, now, true)) {
                    suspend(encounter, now, "warned_approach_unavailable");
                }
            }
        }
        roundRobinOffset = DirectorPolicy.nextRoundRobinOffset(roundRobinOffset, active.size());
    }

    private void playWarning(ServerLevel level, List<ServerPlayer> players, Encounter encounter) {
        List<ResourceLocation> sounds = encounter.blend == null ? List.of(new ResourceLocation("minecraft", "entity.zombie.ambient"))
                : encounter.blend.choose(random).warningSounds();
        if (sounds.isEmpty()) return;
        SpawnLocator.approach(level, players, random).ifPresent(position -> {
            SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(sounds.get(random.nextInt(sounds.size())));
            if (sound != null) {
                level.playSound(null, position, sound, SoundSource.HOSTILE, 0.75F, 0.85F + random.nextFloat() * 0.25F);
                for (ServerPlayer player : players) if(player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(position)) < 16 * 16)
                    WarningAdmission.publish(player, encounter.id, position);
            }
        });
    }

    private boolean beginPacketTelegraph(ServerLevel level, List<ServerPlayer> players, Encounter encounter, long now,
                                         boolean continuation) {
        cancelPacketTelegraph(encounter);
        int sector = encounter.profile.maximizeDirections() ? encounter.nextSector & 7 : -1;
        return SpawnLocator.approach(level, players, random, sector).map(position -> {
            encounter.packetTelegraphPosition = position;
            encounter.packetTelegraphSector = sector;
            encounter.packetTelegraphContinuation = continuation;
            encounter.packetTelegraphUntil = now + DirectorPolicy.PACKET_TELEGRAPH_TICKS;
            playEcologySound(level, position, encounter);
            playPacketTelegraph(level, players, encounter, now);
            return true;
        }).orElse(false);
    }

    private void playPacketTelegraph(ServerLevel level, List<ServerPlayer> players, Encounter encounter, long now) {
        BlockPos approach = encounter.packetTelegraphPosition;
        if (approach == null || encounter.packetTelegraphUntil < 0L) return;
        if ((encounter.packetTelegraphUntil - now) % 10L == 0L) {
            level.playSound(null, approach, net.minecraft.sounds.SoundEvents.RAVAGER_STEP,
                    SoundSource.HOSTILE, 1.15F, 0.55F);
        }
        if (now % 4L == 0L) {
            BlockParticleOption debris = new BlockParticleOption(ParticleTypes.FALLING_DUST,
                    Blocks.STONE.defaultBlockState());
            for (ServerPlayer player : players) {
                if(level.sendParticles(player, debris, true, player.getX(), player.getY() + 2.2,
                        player.getZ(), 3, 1.4, 0.25, 1.4, 0.02))
                    WarningAdmission.publish(player, encounter.id, approach);
            }
        }
    }

    private void playEcologySound(ServerLevel level, BlockPos position, Encounter encounter) {
        List<ResourceLocation> sounds = encounter.blend == null
                ? List.of(new ResourceLocation("minecraft", "entity.zombie.ambient"))
                : encounter.blend.choose(random).warningSounds();
        if (sounds.isEmpty()) return;
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(sounds.get(random.nextInt(sounds.size())));
        if (sound != null) level.playSound(null, position, sound, SoundSource.HOSTILE,
                0.9F, 0.75F + random.nextFloat() * 0.15F);
    }

    private static void cancelPacketTelegraph(Encounter encounter) {
        encounter.packetTelegraphUntil = -1L;
        encounter.packetTelegraphPosition = null;
        encounter.packetTelegraphSector = -1;
        encounter.packetTelegraphContinuation = false;
    }

    private static void completePacketTelegraph(Encounter encounter) {
        encounter.packetTelegraphUntil = -1L;
        encounter.packetTelegraphContinuation = false;
    }

    private void suspend(Encounter encounter, long now, String reason) {
        encounter.suspensionReason = reason;
        encounter.lastFailure = reason;
        if (encounter.phase == DirectorPolicy.Phase.SUSPENDED) return;
        encounter.suspendedPhase = encounter.phase;
        encounter.suspendedTicks = Math.max(0L, encounter.phaseUntil - now);
        encounter.phase = DirectorPolicy.Phase.SUSPENDED;
        encounter.queuedSpawns = 0;
        cancelPacketTelegraph(encounter);
    }

    private boolean resumeIfPursuitAvailable(MinecraftServer server, Encounter encounter, long now) {
        List<ServerPlayer> pursuit = encounter.pursuitPlayers(server, this::eligible);
        if (pursuit.isEmpty()) {
            encounter.suspensionReason = "no_eligible_pursuit";
            encounter.lastFailure = encounter.suspensionReason;
            return false;
        }
        if (!SpawnLocator.hasApproach(pursuit.get(0).serverLevel(), pursuit, random)) {
            encounter.suspensionReason = "route_unavailable";
            encounter.lastFailure = encounter.suspensionReason;
            return false;
        }
        encounter.phase = encounter.suspendedPhase;
        encounter.phaseUntil = now + encounter.suspendedTicks;
        encounter.suspendedTicks = 0L;
        encounter.suspensionReason = "none";
        return true;
    }

    private void beginRecovery(MinecraftServer server, Encounter encounter, long now) {
        encounter.phase = DirectorPolicy.Phase.RECOVERY;
        encounter.queuedSpawns = DirectorPolicy.queuedWorkAfterTransition(encounter.queuedSpawns, encounter.phase);
        cancelPacketTelegraph(encounter);
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
        // The mobs remain ordinary hostile entities after the encounter ends; stop
        // attributing newly loaded or missing entities to the retired encounter.
        mobEncounter.entrySet().removeIf(entry -> entry.getValue().equals(encounter.id));
        iterator.remove();
    }

    private void cleanupMobs(MinecraftServer server) {
        directorMobs.removeIf(id -> {
            Entity entity = findEntity(server, id);
            if (!(entity instanceof Mob mob)) { mobEncounter.remove(id); return true; }
            SpawnLocator.restoreDirectorMob(mob);
            return false;
        });
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

    private static void updateInjuryRelief(MinecraftServer server) {
        DirectorSavedData data = DirectorSavedData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            data.track(player.getUUID()).observeInjuries(InjuryCompat.activeMaimCount(player),
                    DirectorConfig.INJURY_RELIEF_DECAY_SECONDS.get());
        }
    }

    private static double averageInjuryRelief(Collection<ServerPlayer> players, DirectorSavedData data) {
        return players.stream().mapToDouble(player -> data.track(player.getUUID()).injuryRelief()).average().orElse(0.0);
    }

    private static double healthRatio(Collection<ServerPlayer> players) {
        double health = 0.0;
        double maximum = 0.0;
        for (ServerPlayer player : players) {
            health += Math.max(0.0, InjuryCompat.semanticHealth(player));
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
        private final UUID family;
        private final List<UUID> participants;
        private UUID pursuitTarget;
        private final EcologyRegistry.Blend blend;
        private final double depth;
        private final DirectorPolicy.Profile profile;
        private DirectorPolicy.Phase phase = DirectorPolicy.Phase.WARNING;
        private long phaseUntil;
        private long lastPacketAt = -1L;
        private int remainingBudget;
        private int spentBudget;
        private int queuedSpawns;
        private int nextSector;
        private boolean heavySpawnedInPacket;
        private long packetTelegraphUntil = -1L;
        private BlockPos packetTelegraphPosition;
        private int packetTelegraphSector = -1;
        private boolean packetTelegraphContinuation;
        private long localitySeparatedSince = -1L;
        private DirectorPolicy.Phase suspendedPhase = DirectorPolicy.Phase.WARNING;
        private long suspendedTicks;
        private String suspensionReason = "none";
        private String lastFailure = "none";
        private final Map<ResourceLocation, Integer> packetCounts = new HashMap<>();
        private final Map<ResourceLocation, Integer> encounterCounts = new HashMap<>();

        private Encounter(UUID id, List<UUID> participants, EcologyRegistry.Blend blend, double depth,
                          DirectorPolicy.Profile profile, long phaseUntil) {
            this.id = id;
            this.family = id;
            this.participants = new ArrayList<>(participants);
            this.pursuitTarget = this.participants.stream().min(UUID::compareTo).orElse(null);
            this.blend = blend;
            this.depth = depth;
            this.profile = profile;
            this.phaseUntil = phaseUntil;
            this.remainingBudget = profile.budgetPerPlayer() * participants.size();
        }

        private Encounter(UUID id, UUID family, List<UUID> participants, EcologyRegistry.Blend blend, double depth,
                          DirectorPolicy.Profile profile, long phaseUntil, int remainingBudget, int spentBudget) {
            this.id = id;
            this.family = family;
            this.participants = new ArrayList<>(participants);
            this.pursuitTarget = this.participants.stream().min(UUID::compareTo).orElse(null);
            this.blend = blend;
            this.depth = depth;
            this.profile = profile;
            this.phaseUntil = phaseUntil;
            this.remainingBudget = Math.max(0, remainingBudget);
            this.spentBudget = Math.max(0, spentBudget);
        }

        private static Encounter split(Encounter parent, List<UUID> participants, int remainingBudget, int spentBudget) {
            Encounter child = new Encounter(UUID.randomUUID(), parent.family, participants, parent.blend, parent.depth,
                    parent.profile, parent.phaseUntil, remainingBudget, spentBudget);
            child.phase = parent.phase;
            child.lastPacketAt = parent.lastPacketAt;
            child.nextSector = parent.nextSector;
            child.suspendedPhase = parent.suspendedPhase;
            child.suspendedTicks = parent.suspendedTicks;
            child.suspensionReason = parent.suspensionReason;
            child.lastFailure = parent.lastFailure;
            child.pursuitTarget = participants.contains(parent.pursuitTarget) ? parent.pursuitTarget : child.pursuitTarget;
            return child;
        }

        private static Encounter merge(List<Encounter> family) {
            Encounter template = family.get(0);
            List<UUID> participants = family.stream().flatMap(encounter -> encounter.participants.stream()).sorted().toList();
            int remaining = family.stream().mapToInt(encounter -> encounter.remainingBudget).sum();
            int spent = family.stream().mapToInt(encounter -> encounter.spentBudget).sum();
            DirectorPolicy.Phase phase = family.stream().map(encounter -> encounter.phase)
                    .max(Comparator.comparingInt(Encounter::phaseProgress)).orElse(DirectorPolicy.Phase.WARNING);
            long phaseUntil = family.stream().filter(encounter -> encounter.phase == phase)
                    .mapToLong(encounter -> encounter.phaseUntil).max().orElse(template.phaseUntil);
            double depth = family.stream().mapToDouble(encounter -> encounter.depth * encounter.participants.size()).sum()
                    / Math.max(1, participants.size());
            Encounter merged = new Encounter(UUID.randomUUID(), template.family, participants, template.blend, depth,
                    template.profile, phaseUntil, remaining, spent);
            merged.phase = phase;
            merged.lastPacketAt = family.stream().mapToLong(encounter -> encounter.lastPacketAt).max().orElse(-1L);
            merged.nextSector = family.stream().mapToInt(encounter -> encounter.nextSector).min().orElse(0);
            merged.heavySpawnedInPacket = family.stream().anyMatch(encounter -> encounter.heavySpawnedInPacket);
            merged.suspendedPhase = family.stream().map(encounter -> encounter.suspendedPhase)
                    .max(Comparator.comparingInt(Encounter::phaseProgress)).orElse(DirectorPolicy.Phase.WARNING);
            merged.suspendedTicks = family.stream().mapToLong(encounter -> encounter.suspendedTicks).max().orElse(0L);
            merged.suspensionReason = family.stream().filter(encounter -> encounter.phase == DirectorPolicy.Phase.SUSPENDED)
                    .map(encounter -> encounter.suspensionReason).filter(reason -> !"none".equals(reason))
                    .findFirst().orElse("none");
            merged.lastFailure = family.stream().map(encounter -> encounter.lastFailure)
                    .filter(reason -> !"none".equals(reason)).findFirst().orElse("none");
            merged.pursuitTarget = family.stream().map(encounter -> encounter.pursuitTarget)
                    .filter(participants::contains).min(UUID::compareTo).orElse(merged.pursuitTarget);
            family.forEach(encounter -> encounter.encounterCounts.forEach((entity, count) ->
                    merged.encounterCounts.merge(entity, count, Integer::sum)));
            return merged;
        }

        private static int phaseProgress(DirectorPolicy.Phase phase) {
            return switch (phase) {
                case WARNING -> 0;
                case SURGE -> 1;
                case RECOVERY -> 2;
                case SUSPENDED -> 3;
                case RETIRED -> 4;
            };
        }

        private List<ServerPlayer> players(MinecraftServer server) {
            return participants.stream().map(server.getPlayerList()::getPlayer).filter(java.util.Objects::nonNull).toList();
        }

        private List<ServerPlayer> pursuitPlayers(MinecraftServer server, Predicate<ServerPlayer> eligible) {
            List<ServerPlayer> available = participants.stream()
                    .map(server.getPlayerList()::getPlayer)
                    .filter(java.util.Objects::nonNull)
                    .filter(eligible)
                    .toList();
            pursuitTarget = DirectorPolicy.pursuitTarget(pursuitTarget, participants,
                    available.stream().map(ServerPlayer::getUUID).toList());
            if (pursuitTarget == null) return List.of();
            return available.stream().filter(player -> player.getUUID().equals(pursuitTarget)).toList();
        }

        private void replacePursuitTarget() {
            if (pursuitTarget == null || !participants.contains(pursuitTarget)) {
                pursuitTarget = participants.stream().min(UUID::compareTo).orElse(null);
            }
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
