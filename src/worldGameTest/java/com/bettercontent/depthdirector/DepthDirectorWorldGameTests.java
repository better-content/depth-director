package com.bettercontent.depthdirector.worldtest;

import com.bettercontent.depthdirector.DepthDirectorMod;
import com.bettercontent.depthdirector.DepthMath;
import com.bettercontent.depthdirector.DirectorSavedData;
import com.bettercontent.depthdirector.EcologyDefinition;
import com.bettercontent.depthdirector.EcologyRegistry;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@GameTestHolder("depth_director_world_tests")
@PrefixGameTestTemplate(false)
public final class DepthDirectorWorldGameTests {
    private static final String TEMPLATE = "director_cave";
    private static final int TIMEOUT_TICKS = 80_000;
    private static final int[] DEPTHS = {-56, 0, 32};
    private static final long DIRECTOR_SEED = 0x5EED_D1EC70L;
    private static final int ARENA_SEPARATION = 224;
    private static final double ARENA_SEPARATION_SQUARED = ARENA_SEPARATION * ARENA_SEPARATION;
    private static final Set<ResourceLocation> FORBIDDEN_BASE_HOSTILES = Set.of(
            new ResourceLocation("minecraft", "zombie"),
            new ResourceLocation("minecraft", "skeleton"));

    private DepthDirectorWorldGameTests() {}

    @GameTest(templateNamespace = DepthDirectorWorldTestMod.MOD_ID, template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public static void realCataloguePlayersAndNaturalDepthCadence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        ProductionAccess.reset(DIRECTOR_SEED);
        discardDirectorMobs(level, new AABB(-30_000, level.getMinBuildHeight(), -30_000,
                30_000, level.getMaxBuildHeight(), 30_000));

        WorldProof proof = WorldProof.create(helper);
        proof.buildWorld();
        proof.begin(level.getGameTime());
        helper.onEachTick(proof::tick);
    }

    private static final class WorldProof {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final List<EcologyDefinition> ecologies;
        private final List<BlockPos> reserved = new ArrayList<>();
        private final List<Cohort> cohorts = new ArrayList<>();
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<PlannedCohort> planned = new ArrayList<>();
        private BlockPos catalogueCenter;
        private BlockPos surfaceCenter;
        private ServerPlayer cataloguePlayer;
        private SurfaceSentinel surface;
        private int complete;
        private int playerNumber;
        private int setupPhase;
        private long setupDeadline;
        private boolean started;

        private WorldProof(GameTestHelper helper, List<EcologyDefinition> ecologies) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.ecologies = ecologies;
        }

        static WorldProof create(GameTestHelper helper) {
            List<EcologyDefinition> definitions = EcologyRegistry.INSTANCE.definitions().values().stream()
                    .sorted(Comparator.comparing(definition -> definition.id().toString()))
                    .toList();
            helper.assertTrue(definitions.size() == 5,
                    "the real reload listener must load exactly five authored ecologies, loaded="
                            + definitions.stream().map(EcologyDefinition::id).toList());
            helper.assertTrue(definitions.stream().map(EcologyDefinition::id).collect(java.util.stream.Collectors.toSet())
                            .containsAll(List.of(id("undead"), id("carrion"), id("spirits"), id("end"), id("sculk"))),
                    "the real catalogue must contain undead, carrion, spirits, end, and sculk");
            return new WorldProof(helper, definitions);
        }

        void buildWorld() {
            BlockPos catalogueHorizontal = helper.absolutePos(new BlockPos(-256, 0, -256));
            catalogueCenter = new BlockPos(catalogueHorizontal.getX(), -24, catalogueHorizontal.getZ());
            buildArena(level, catalogueCenter);
        }

        void begin(long now) {
            setupDeadline = now + 50;
        }

        void buildCohortWorld() {
            for (EcologyDefinition ecology : ecologies) {
                for (int y : DEPTHS) {
                    BlockPos center = findPureTerritory(ecology.id(), y);
                    reserved.add(center);
                    planned.add(new PlannedCohort(ecology, y, center));
                    buildArena(level, center);
                }
            }
            surfaceCenter = findSurfacePosition();
            buildSurfacePad(level, surfaceCenter);
        }

        void qualifyCatalogue() {
            validateCatalogueEntries();
            cataloguePlayer = registeredPlayer(catalogueCenter);
            BlockPos candidate = roomPosition(catalogueCenter, 0, 42);

            for (EcologyDefinition ecology : ecologies) {
                for (EcologyDefinition.Entry entry : ecology.roster()) {
                    EcologyDefinition single = singleEntry(ecology, entry);
                    WorldSpawn result = ProductionAccess.spawnAt(level, List.of(cataloguePlayer),
                            new EcologyRegistry.Blend(single, null, 0.0), 1.0,
                            RandomSource.create(DIRECTOR_SEED ^ entry.entity().hashCode()), candidate, true, 64);
                    helper.assertTrue(result.spawned(),
                            "real EVENT spawn failed for " + ecology.id() + " entry " + entry.entity()
                                    + "; " + ProductionAccess.diagnose(level, List.of(cataloguePlayer),
                                    entry.entity(), candidate));
                    helper.assertTrue(entry.entity().equals(result.entity()),
                            "single-entry ecology selected the wrong entity for " + entry.entity());
                    helper.assertTrue(result.mob() != null && result.mob().getTarget() == cataloguePlayer,
                            "real spawn did not target the registered nearest player for " + entry.entity());
                    assertProvenance(result.mob(), "catalogue entry " + entry.entity());
                    ProductionAccess.removeMob(result.mob().getUUID());
                    result.mob().discard();
                }
            }
            removePlayer(cataloguePlayer);
            cataloguePlayer = null;
            DepthDirectorMod.LOGGER.info("Director verifyWorld catalogue qualification passed for {} real entries",
                    ecologies.stream().mapToInt(ecology -> ecology.roster().size()).sum());
        }

        private void validateCatalogueEntries() {
            Set<ResourceLocation> seen = new LinkedHashSet<>();
            for (EcologyDefinition ecology : ecologies) {
                helper.assertTrue(!ecology.roster().isEmpty(), ecology.id() + " must have a non-empty roster");
                for (ResourceLocation sound : ecology.warningSounds()) {
                    helper.assertTrue(ForgeRegistries.SOUND_EVENTS.containsKey(sound),
                            ecology.id() + " warning sound is missing from the exact world-test mod set: " + sound);
                }
                for (EcologyDefinition.Entry entry : ecology.roster()) {
                    helper.assertTrue(!FORBIDDEN_BASE_HOSTILES.contains(entry.entity()),
                            ecology.id() + " illegally contains base zombie/skeleton: " + entry.entity());
                    helper.assertTrue(seen.add(entry.entity()),
                            "entity appears in more than one ecology: " + entry.entity());
                    EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entry.entity());
                    helper.assertTrue(type != null,
                            ecology.id() + " entity is missing from the exact world-test mod set: " + entry.entity());
                    helper.assertTrue(type != null && type.getCategory() == MobCategory.MONSTER,
                            ecology.id() + " entity is not registered as a monster: " + entry.entity());
                    Mob mob = type == null ? null : type.create(level) instanceof Mob created ? created : null;
                    helper.assertTrue(mob != null, ecology.id() + " entity does not create a Mob: " + entry.entity());
                    if (mob != null) mob.discard();
                }
            }
        }

        void start() {
            for (PlannedCohort plan : planned) {
                ServerPlayer player = registeredPlayer(plan.center());
                DirectorSavedData.Track track = DirectorSavedData.get(level.getServer()).track(player.getUUID());
                cohorts.add(new Cohort(plan.ecology(), plan.y(), plan.center(), player, track));
            }
            ServerPlayer surfacePlayer = registeredPlayer(surfaceCenter);
            surface = new SurfaceSentinel(surfaceCenter, surfacePlayer,
                    DirectorSavedData.get(level.getServer()).track(surfacePlayer.getUUID()));
            helper.assertTrue(level.getServer().getPlayerList().getPlayers().containsAll(players),
                    "all 16 dummy players must be registered in the real server player list");
            started = true;
            DepthDirectorMod.LOGGER.info("Director verifyWorld started 15 concurrent natural cohorts plus surface sentinel");
        }

        void tick() {
            try {
                long now = level.getGameTime();
                if (!started) {
                    if (setupPhase == 0 && now >= setupDeadline) {
                        qualifyCatalogue();
                        buildCohortWorld();
                        setupPhase = 1;
                        setupDeadline = level.getGameTime() + 40;
                    } else if (setupPhase == 1 && now >= setupDeadline) {
                        start();
                        setupPhase = 2;
                    }
                    return;
                }
                surface.tick(now);
                for (Cohort cohort : cohorts) {
                    if (!cohort.complete) cohort.tick(now);
                }
                if (complete == cohorts.size()) {
                    surface.assertFinal();
                    DepthDirectorMod.LOGGER.info("Director verifyWorld passed all {} real-player cohorts at game time {}",
                            complete, now);
                    cleanup();
                    helper.succeed();
                }
            } catch (RuntimeException exception) {
                cleanup();
                throw exception;
            }
        }

        void cleanup() {
            for (ServerPlayer player : List.copyOf(players)) removePlayer(player);
            discardDirectorMobs(level, new AABB(-30_000, level.getMinBuildHeight(), -30_000,
                    30_000, level.getMaxBuildHeight(), 30_000));
            ProductionAccess.reset();
            started = false;
        }

        private ServerPlayer registeredPlayer(BlockPos center) {
            int number = playerNumber++;
            String name = "director-" + number;
            GameProfile profile = new GameProfile(java.util.UUID.nameUUIDFromBytes(
                    ("depth-director-world-" + number).getBytes(StandardCharsets.UTF_8)), name);
            ServerPlayer player = new ServerPlayer(level.getServer(), level, profile);
            player.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection, player);
            GameType mode = player.gameMode.getGameModeForPlayer();
            helper.assertTrue(mode == GameType.SURVIVAL || mode == GameType.ADVENTURE,
                    "registered dummy player must inherit an eligible server game mode, got " + mode);
            player.setInvulnerable(true);
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            player.setDeltaMovement(Vec3.ZERO);
            DirectorSavedData.get(level.getServer()).reset(player.getUUID());
            players.add(player);
            return player;
        }

        private void removePlayer(ServerPlayer player) {
            if (player == null || !players.remove(player)) return;
            ProductionAccess.playerDied(level.getServer(), player.getUUID());
            level.getServer().getPlayerList().remove(player);
        }

        private BlockPos findPureTerritory(ResourceLocation ecology, int y) {
            BlockPos template = helper.absolutePos(BlockPos.ZERO);
            int offset = Math.floorMod(ecology.hashCode() * 31 + y * 17, 127);
            for (int index = 0; index < 40_000; index++) {
                int grid = index + offset;
                int radius = (int) Math.ceil(Math.sqrt(grid + 1));
                int side = radius * 2 + 1;
                int xCell = Math.floorMod(grid * 73, side) - radius;
                int zCell = Math.floorMod(grid * 151 + grid / Math.max(1, side), side) - radius;
                BlockPos candidate = new BlockPos(template.getX() + 768 + xCell * 128, y,
                        template.getZ() + 768 + zCell * 128);
                if (horizontalDistanceSquared(candidate, template) < 512.0 * 512.0) continue;
                if (reserved.stream().anyMatch(other -> horizontalDistanceSquared(candidate, other)
                        < ARENA_SEPARATION_SQUARED)) continue;
                EcologyRegistry.Blend blend = EcologyRegistry.INSTANCE.blend(level.getSeed(),
                        new Vec3(candidate.getX() + 0.5, y, candidate.getZ() + 0.5));
                if (blend != null && ecology.equals(blend.primary().id()) && blend.secondary() == null) return candidate;
            }
            throw new IllegalStateException("could not locate an isolated pure natural territory for " + ecology + " at Y " + y);
        }

        private BlockPos findSurfacePosition() {
            BlockPos template = helper.absolutePos(BlockPos.ZERO);
            for (int ring = 1; ring < 128; ring++) {
                int x = template.getX() - 1024 - ring * 128;
                int z = template.getZ() - 1024 - ring * 128;
                BlockPos horizontalCandidate = new BlockPos(x, level.getMinBuildHeight(), z);
                if (reserved.stream().anyMatch(other -> horizontalDistanceSquared(horizontalCandidate, other)
                        < 512.0 * 512.0)) continue;
                forceFixtureChunks(level, horizontalCandidate, 1);
                level.getChunk(x >> 4, z >> 4);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                return candidate;
            }
            throw new IllegalStateException("could not reserve an isolated surface sentinel");
        }

        private final class Cohort {
            private final EcologyDefinition ecology;
            private final int y;
            private final BlockPos center;
            private final ServerPlayer player;
            private final DirectorSavedData.Track track;
            private long firstPressureTick = -1;
            private long expectedWarningTick = -1;
            private long warningTick = -1;
            private boolean complete;

            private Cohort(EcologyDefinition ecology, int y, BlockPos center, ServerPlayer player,
                           DirectorSavedData.Track track) {
                this.ecology = ecology;
                this.y = y;
                this.center = center;
                this.player = player;
                this.track = track;
            }

            private void tick(long now) {
                hold(player, center);
                String inspection = ProductionAccess.inspect(player);
                helper.assertTrue(ecology.id().toString().equals(field(inspection, "ecology")),
                        label() + " left its pure natural territory: " + inspection);
                helper.assertTrue(track.probeFailures() < 3,
                        label() + " failed three real route probes: " + inspection);

                if (firstPressureTick < 0 && track.pressure() > 0.0) {
                    firstPressureTick = now;
                    int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            center.getX(), center.getZ());
                    double depth = DepthMath.depthFactor(y,
                            DepthMath.controlCeiling(surfaceY, 6), level.getMinBuildHeight());
                    double cadence = ProductionAccess.cadenceSeconds(ecology.cadenceMinimumSeconds(),
                            ecology.cadenceMaximumSeconds(), 0.5);
                    int updates = (int) Math.ceil(cadence / depth - 1.0e-9);
                    expectedWarningTick = firstPressureTick + (long) (updates - 1) * 20L;
                }

                String phase = field(inspection, "phase");
                if (warningTick < 0 && "warning".equals(phase)) {
                    warningTick = now;
                    helper.assertTrue(expectedWarningTick >= 0,
                            label() + " warned before an eligible real pressure update");
                    helper.assertTrue(Math.abs(warningTick - expectedWarningTick) <= 20,
                            label() + " warning cadence was not natural: expected " + expectedWarningTick
                                    + " +/-20, observed " + warningTick + "; " + inspection);
                } else if (warningTick < 0) {
                    helper.assertTrue(expectedWarningTick < 0 || now <= expectedWarningTick + 20,
                            label() + " did not enter warning at its natural full-pressure tick: " + inspection);
                    helper.assertTrue("build_up".equals(phase),
                            label() + " entered an unexpected pre-warning phase: " + inspection);
                }

                List<Mob> spawned = nearbyDirectorMobs(level, center);
                if (spawned.isEmpty()) return;
                helper.assertTrue(warningTick >= 0, label() + " spawned before the real warning phase");
                long earliest = warningTick + ecology.warningMinimumSeconds() * 20L;
                long latest = warningTick + ecology.warningMaximumSeconds() * 20L + 2_400L;
                helper.assertTrue(now >= earliest && now <= latest,
                        label() + " first spawn was outside warning plus geometry allowance: warning="
                                + warningTick + " spawn=" + now + " allowed=" + earliest + ".." + latest);
                Set<ResourceLocation> roster = ecology.roster().stream().map(EcologyDefinition.Entry::entity)
                        .collect(java.util.stream.Collectors.toSet());
                for (Mob mob : spawned) {
                    ResourceLocation entity = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
                    helper.assertTrue(roster.contains(entity),
                            label() + " spawned an entity outside its allocated roster: " + entity);
                    helper.assertTrue(!FORBIDDEN_BASE_HOSTILES.contains(entity),
                            label() + " spawned a forbidden base zombie/skeleton: " + entity);
                    helper.assertTrue(mob.getTarget() == player,
                            label() + " spawn did not target its isolated registered player: " + entity);
                    assertProvenance(mob, label() + " first packet");
                }
                complete = true;
                WorldProof.this.complete++;
                DepthDirectorMod.LOGGER.info("Director verifyWorld cohort passed ecology={} y={} warningTick={} spawnTick={} entities={}",
                        ecology.id(), y, warningTick, now, spawned.stream()
                                .map(mob -> ForgeRegistries.ENTITY_TYPES.getKey(mob.getType())).toList());
                for (Mob mob : spawned) {
                    ProductionAccess.removeMob(mob.getUUID());
                    mob.discard();
                }
                removePlayer(player);
            }

            private String label() { return ecology.id() + "@Y" + y; }
        }

        private final class SurfaceSentinel {
            private final BlockPos center;
            private final ServerPlayer player;
            private final DirectorSavedData.Track track;

            private SurfaceSentinel(BlockPos center, ServerPlayer player, DirectorSavedData.Track track) {
                this.center = center;
                this.player = player;
                this.track = track;
            }

            private void tick(long now) {
                hold(player, center);
                if (now % 20L != 0L) return;
                String inspection = ProductionAccess.inspect(player);
                helper.assertTrue(track.pressure() == 0.0,
                        "surface sentinel accumulated pressure: " + inspection);
                helper.assertTrue("build_up".equals(field(inspection, "phase")),
                        "surface sentinel entered an encounter: " + inspection);
                helper.assertTrue(nearbyDirectorMobs(level, center).isEmpty(),
                        "surface sentinel received a Director spawn");
            }

            private void assertFinal() { tick(level.getGameTime()); }
        }

        private void hold(ServerPlayer player, BlockPos center) {
            if (player == null || !players.contains(player)) return;
            player.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    private static EcologyDefinition singleEntry(EcologyDefinition ecology, EcologyDefinition.Entry entry) {
        return new EcologyDefinition(ecology.id(), ecology.noiseScale(), ecology.cadenceMinimumSeconds(),
                ecology.cadenceMaximumSeconds(), ecology.warningMinimumSeconds(), ecology.warningMaximumSeconds(),
                ecology.surgeSeconds(), ecology.recoverySeconds(), ecology.deepBudgetPerPlayer(),
                ecology.deepActiveTargetPerPlayer(), ecology.packetIntervalTicks(), ecology.maximizeDirections(),
                ecology.warningSounds(), List.of(entry));
    }

    private static void assertProvenance(Mob mob, String context) {
        if (mob == null || !mob.getTags().contains(ProductionAccess.PROVENANCE_TAG)
                || !mob.getPersistentData().getBoolean(ProductionAccess.PROVENANCE_NBT)) {
            throw new IllegalStateException(context + " lacks both Director provenance markers");
        }
    }

    private static List<Mob> nearbyDirectorMobs(ServerLevel level, BlockPos center) {
        AABB bounds = AABB.ofSize(Vec3.atCenterOf(center), 192.0, 192.0, 192.0);
        return level.getEntitiesOfClass(Mob.class, bounds, mob -> mob.getTags().contains(ProductionAccess.PROVENANCE_TAG)
                || mob.getPersistentData().getBoolean(ProductionAccess.PROVENANCE_NBT));
    }

    private static void discardDirectorMobs(ServerLevel level, AABB bounds) {
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds, candidate ->
                candidate.getTags().contains(ProductionAccess.PROVENANCE_TAG)
                        || candidate.getPersistentData().getBoolean(ProductionAccess.PROVENANCE_NBT))) {
            ProductionAccess.removeMob(mob.getUUID());
            mob.discard();
        }
    }

    private static void buildArena(ServerLevel level, BlockPos center) {
        forceFixtureChunks(level, center, 5);
        Set<Xz> cells = new HashSet<>();
        carveDisc(cells, center.getX(), center.getZ(), 5.0);
        for (int sector = 0; sector < 8; sector++) {
            double angle = (sector + 0.5) * Math.PI / 4.0;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            for (int dx = -68; dx <= 68; dx++) {
                for (int dz = -68; dz <= 68; dz++) {
                    double radial = dx * cos + dz * sin;
                    double tangent = -dx * sin + dz * cos;
                    if (radial >= 25.0 && radial <= 67.0 && Math.abs(tangent) <= 8.0) {
                        cells.add(new Xz(center.getX() + dx, center.getZ() + dz));
                    }
                }
            }
            carveLine(cells, center, point(center, angle + 0.62, 11), 4.0);
            carveLine(cells, point(center, angle + 0.62, 11), point(center, angle + 0.62, 19), 4.0);
            carveLine(cells, point(center, angle + 0.62, 19), point(center, angle, 29), 4.0);
        }

        int floor = center.getY() - 1;
        int roof = center.getY() + 7;
        for (Xz cell : cells) {
            set(level, new BlockPos(cell.x, floor, cell.z), Blocks.DEEPSLATE.defaultBlockState());
            for (int y = center.getY(); y < roof; y++) {
                set(level, new BlockPos(cell.x, y, cell.z), Blocks.AIR.defaultBlockState());
            }
            set(level, new BlockPos(cell.x, roof, cell.z), Blocks.DEEPSLATE.defaultBlockState());
        }
        for (Xz cell : cells) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Xz wall = new Xz(cell.x + dx, cell.z + dz);
                    if (cells.contains(wall)) continue;
                    for (int y = floor; y <= roof; y++) {
                        set(level, new BlockPos(wall.x, y, wall.z), Blocks.DEEPSLATE.defaultBlockState());
                    }
                }
            }
        }
        for (int y = roof; y < center.getY() + 40; y++) {
            set(level, new BlockPos(center.getX(), y, center.getZ()), Blocks.DEEPSLATE.defaultBlockState());
        }
    }

    private static void buildSurfacePad(ServerLevel level, BlockPos center) {
        forceFixtureChunks(level, center, 1);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                set(level, center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 3; y++) set(level, center.offset(dx, y, dz), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void carveLine(Set<Xz> cells, BlockPos from, BlockPos to, double radius) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ())) * 2;
        for (int step = 0; step <= steps; step++) {
            double amount = steps == 0 ? 0.0 : (double) step / steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * amount);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * amount);
            carveDisc(cells, x, z, radius);
        }
    }

    private static void carveDisc(Set<Xz> cells, int x, int z, double radius) {
        int extent = (int) Math.ceil(radius);
        for (int dx = -extent; dx <= extent; dx++) {
            for (int dz = -extent; dz <= extent; dz++) {
                if (dx * dx + dz * dz <= radius * radius) cells.add(new Xz(x + dx, z + dz));
            }
        }
    }

    private static BlockPos point(BlockPos center, double angle, int radius) {
        return new BlockPos(center.getX() + (int) Math.round(Math.cos(angle) * radius), center.getY(),
                center.getZ() + (int) Math.round(Math.sin(angle) * radius));
    }

    private static BlockPos roomPosition(BlockPos center, int sector, int radius) {
        return point(center, (sector + 0.5) * Math.PI / 4.0, radius);
    }

    private static void set(ServerLevel level, BlockPos position, net.minecraft.world.level.block.state.BlockState state) {
        level.setBlock(position, state, 2);
    }

    private static void forceFixtureChunks(ServerLevel level, BlockPos center, int radius) {
        int centerX = center.getX() >> 4;
        int centerZ = center.getZ() >> 4;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) level.setChunkForced(x, z, true);
        }
    }

    private static String field(String inspection, String key) {
        String prefix = key + "=";
        for (String token : inspection.split(" ")) {
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        throw new IllegalStateException("missing " + key + " in Director inspection: " + inspection);
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(DepthDirectorMod.MOD_ID, path);
    }

    private static final class ProductionAccess {
        private static final Object RUNTIME = staticField("com.bettercontent.depthdirector.DirectorRuntime", "INSTANCE");
        private static final Class<?> RUNTIME_CLASS = RUNTIME.getClass();
        private static final Class<?> SPAWN_CLASS = type("com.bettercontent.depthdirector.SpawnLocator");
        private static final Class<?> POLICY_CLASS = type("com.bettercontent.depthdirector.DirectorPolicy");
        private static final String PROVENANCE_TAG = (String) staticField(SPAWN_CLASS, "PROVENANCE_TAG");
        private static final String PROVENANCE_NBT = (String) staticField(SPAWN_CLASS, "PROVENANCE_NBT");

        private ProductionAccess() {}

        private static void reset(long seed) {
            invoke(RUNTIME, method(RUNTIME_CLASS, "reset", long.class), seed);
        }

        private static void reset() {
            invoke(RUNTIME, method(RUNTIME_CLASS, "reset"));
        }

        private static String inspect(ServerPlayer player) {
            return (String) invoke(RUNTIME, method(RUNTIME_CLASS, "inspect", ServerPlayer.class), player);
        }

        private static void playerDied(net.minecraft.server.MinecraftServer server, java.util.UUID player) {
            invoke(RUNTIME, method(RUNTIME_CLASS, "playerDied",
                    net.minecraft.server.MinecraftServer.class, java.util.UUID.class), server, player);
        }

        private static void removeMob(java.util.UUID mob) {
            invoke(RUNTIME, method(RUNTIME_CLASS, "removeMob", java.util.UUID.class), mob);
        }

        private static double cadenceSeconds(int minimum, int maximum, double jitter) {
            return (double) invoke(null, method(POLICY_CLASS, "cadenceSeconds",
                    int.class, int.class, double.class), minimum, maximum, jitter);
        }

        private static WorldSpawn spawnAt(ServerLevel level, List<ServerPlayer> players,
                                          EcologyRegistry.Blend blend, double depth, RandomSource random,
                                          BlockPos position, boolean allowHeavy, int maximumCost) {
            Object result = invoke(null, method(SPAWN_CLASS, "spawnAt", ServerLevel.class, List.class,
                    EcologyRegistry.Blend.class, double.class, RandomSource.class, BlockPos.class,
                    boolean.class, int.class), level, players, blend, depth, random, position, allowHeavy, maximumCost);
            Class<?> resultClass = result.getClass();
            return new WorldSpawn((boolean) invoke(result, method(resultClass, "spawned")),
                    (Mob) invoke(result, method(resultClass, "mob")),
                    (ResourceLocation) invoke(result, method(resultClass, "entity")));
        }

        private static String diagnose(ServerLevel level, List<ServerPlayer> players,
                                       ResourceLocation entity, BlockPos position) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entity);
            Mob mob = type == null ? null : type.create(level) instanceof Mob created ? created : null;
            if (mob == null) return "entity does not create a Mob";
            try {
                invoke(null, method(SPAWN_CLASS, "ensureApproachRange", Mob.class), mob);
                Object validation = invoke(null, method(SPAWN_CLASS, "inspectCandidate", ServerLevel.class,
                        List.class, BlockPos.class, Mob.class), level, players, position, mob);
                Object rejection = invoke(validation, method(validation.getClass(), "rejection"));
                if (!"NONE".equals(rejection.toString())) return "candidate rejection=" + rejection;
                mob.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0F, 0.0F);
                boolean eventPosition = (boolean) invoke(null, method(SPAWN_CLASS,
                        "eventSpawnPositionAllowed", Mob.class, ServerLevel.class), mob, level);
                return "candidate accepted, eventPosition=" + eventPosition
                        + ", collisionFree=" + level.noCollision(mob)
                        + ", inBorder=" + level.getWorldBorder().isWithinBounds(mob.getBoundingBox());
            } finally {
                mob.discard();
            }
        }

        private static Class<?> type(String name) {
            try {
                return Class.forName(name);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("cannot load production Director class " + name, exception);
            }
        }

        private static Object staticField(String type, String name) {
            return staticField(type(type), name);
        }

        private static Object staticField(Class<?> type, String name) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("cannot access production field " + type.getName() + "." + name, exception);
            }
        }

        private static Method method(Class<?> type, String name, Class<?>... parameters) {
            try {
                Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("cannot access production method " + type.getName() + "." + name, exception);
            }
        }

        private static Object invoke(Object target, Method method, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (ReflectiveOperationException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("production Director call failed: " + method,
                        cause == null ? exception : cause);
            }
        }
    }

    private record Xz(int x, int z) {}
    private record PlannedCohort(EcologyDefinition ecology, int y, BlockPos center) {}
    private record WorldSpawn(boolean spawned, Mob mob, ResourceLocation entity) {}
}
