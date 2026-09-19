package com.bettercontent.depthdirector;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(DepthDirectorMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DepthDirectorGameTests {
    private static final String TEMPLATE = "director_cave";
    private static final BlockPos PLAYER = new BlockPos(3, 1, 3);
    private static final BlockPos VISIBLE_PLAYER = new BlockPos(9, 1, 11);
    private static final BlockPos SPAWN = new BlockPos(11, 1, 11);
    private static final BlockPos SECOND_SPAWN = new BlockPos(11, 1, 10);
    private static final BlockPos ROOF = new BlockPos(11, 5, 11);
    private static final BlockPos TORCH = new BlockPos(10, 1, 11);

    private DepthDirectorGameTests() {}

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void directorBoundaryTracksLocalLeafIgnoringSurface(GameTestHelper helper) {
        BlockPos column = helper.absolutePos(new BlockPos(4, 1, 4));
        int originalSurface = helper.getLevel().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                column.getX(), column.getZ());
        int raisedY = Math.min(originalSurface + 12, helper.getLevel().getMaxBuildHeight() - 1);
        BlockPos raisedSurfaceBlock = new BlockPos(column.getX(), raisedY, column.getZ());
        helper.getLevel().setBlockAndUpdate(raisedSurfaceBlock, Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(1, () -> {
            int surface = helper.getLevel().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column.getX(), column.getZ());
            int ceiling = DirectorRuntime.controlCeiling(helper.getLevel(), column, 6);
            helper.assertTrue(surface == raisedSurfaceBlock.getY() + 1,
                    "leaf-ignoring heightmap must follow the raised local surface");
            helper.assertTrue(ceiling == surface - 6,
                    "Director ceiling must reserve the surface and six blocks below it");
            helper.assertTrue(!DepthMath.isControlled(ceiling, ceiling),
                    "the inclusive reserved band must remain outside Director control");
            helper.assertTrue(DepthMath.isControlled(ceiling - 1, ceiling),
                    "Director control must begin one block below the reserved band");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void ecologiesLoadAndHiddenDarkReachableGeometryIsAccepted(GameTestHelper helper) {
        buildFixture(helper);
        ServerPlayer player = player(helper, PLAYER);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(EcologyRegistry.INSTANCE.definitions().keySet().containsAll(List.of(
                    id("undead"), id("carrion"), id("spirits"), id("end"), id("sculk"))),
                    "all five authored ecologies must be loaded by the reload listener");
            Mob zombie = zombie(helper);
            SpawnLocator.CandidateValidation validation = SpawnLocator.inspectCandidate(helper.getLevel(), List.of(player),
                    helper.absolutePos(SPAWN), zombie);
            SpawnLocator.Candidate candidate = validation.candidate().orElse(null);
            String pathSummary = pathSummary(zombie, player);
            zombie.discard();
            helper.assertTrue(candidate != null,
                    "hidden dark collision-free alcove must be reachable; rejected as " + validation.rejection()
                            + "; " + pathSummary);
            helper.assertTrue(candidate != null && candidate.target() == player,
                    "the accepted candidate must target the nearest eligible player");
            finish(helper, player);
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void directLineOfSightRejectsCandidate(GameTestHelper helper) {
        buildFixture(helper);
        ServerPlayer player = player(helper, VISIBLE_PLAYER);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(rejection(helper, player) == SpawnLocator.Rejection.VISIBLE,
                    "direct player line of sight must be the rejection reason");
            finish(helper, player);
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 120)
    public static void torchLightRejectsCandidateAfterLightingSettles(GameTestHelper helper) {
        buildFixture(helper);
        ServerPlayer player = player(helper, PLAYER);
        helper.runAfterDelay(10, () -> {
            helper.setBlock(TORCH, Blocks.TORCH);
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(helper.getLevel().getMaxLocalRawBrightness(helper.absolutePos(SPAWN))
                                > DirectorConfig.BLOCK_LIGHT_LIMIT.get(),
                        "torch must illuminate the spawn alcove");
                helper.assertTrue(rejection(helper, player) == SpawnLocator.Rejection.LIT,
                        "block light must be the rejection reason");
                finish(helper, player);
            });
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 120)
    public static void admittedSkylightRejectsCandidate(GameTestHelper helper) {
        buildFixture(helper);
        ServerPlayer player = player(helper, PLAYER);
        helper.runAfterDelay(10, () -> {
            BlockPos absoluteSpawn = helper.absolutePos(SPAWN);
            BlockPos absoluteRoof = helper.absolutePos(ROOF);
            int surface = helper.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    absoluteSpawn.getX(), absoluteSpawn.getZ());
            List<SavedBlock> shaft = new ArrayList<>();
            for (int y = absoluteRoof.getY(); y <= surface; y++) {
                BlockPos position = new BlockPos(absoluteSpawn.getX(), y, absoluteSpawn.getZ());
                shaft.add(new SavedBlock(position, helper.getLevel().getBlockState(position)));
                helper.getLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            }
            helper.succeedWhen(() -> {
                helper.assertTrue(helper.getLevel().canSeeSky(absoluteSpawn),
                        "removing the roof and overburden must admit skylight");
                helper.assertTrue(rejection(helper, player) == SpawnLocator.Rejection.LIT,
                        "skylight must be the rejection reason");
                for (int index = shaft.size() - 1; index >= 0; index--) {
                    SavedBlock saved = shaft.get(index);
                    helper.getLevel().setBlockAndUpdate(saved.position(), saved.state());
                }
                buildFixture(helper);
            });
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void closedGateProducesUnreachablePath(GameTestHelper helper) {
        buildFixture(helper);
        for (int z = 12; z < 14; z++) {
            helper.setBlock(new BlockPos(7, 1, z), Blocks.STONE);
            helper.setBlock(new BlockPos(7, 2, z), Blocks.STONE);
        }
        ServerPlayer player = player(helper, PLAYER);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(rejection(helper, player) == SpawnLocator.Rejection.UNREACHABLE,
                    "the sealed gate must produce an unreachable path");
            finish(helper, player);
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void solidAndFluidOccupationRejectCandidate(GameTestHelper helper) {
        buildFixture(helper);
        ServerPlayer player = player(helper, PLAYER);
        helper.runAfterDelay(10, () -> {
            helper.setBlock(SPAWN, Blocks.STONE);
            helper.assertTrue(rejection(helper, player) == SpawnLocator.Rejection.OCCUPIED,
                    "solid-block occupation must be the rejection reason");
            helper.setBlock(SPAWN, Blocks.WATER);
            helper.assertTrue(rejection(helper, player) == SpawnLocator.Rejection.OCCUPIED,
                    "fluid occupation must be the rejection reason");
            finish(helper, player);
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 120)
    public static void eventAuthoredSpawnTargetsPlayerAndCarriesProvenance(GameTestHelper helper) {
        buildFixture(helper);
        ServerPlayer player = player(helper, PLAYER);
        helper.runAfterDelay(10, () -> {
            EcologyDefinition loaded = EcologyRegistry.INSTANCE.definitions().get(id("undead"));
            helper.assertTrue(loaded != null, "undead ecology must be loaded");
            EcologyDefinition zombieOnly = new EcologyDefinition(loaded.id(), loaded.noiseScale(),
                    loaded.cadenceMinimumSeconds(), loaded.cadenceMaximumSeconds(), loaded.warningMinimumSeconds(),
                    loaded.warningMaximumSeconds(), loaded.surgeSeconds(), loaded.recoverySeconds(),
                    loaded.deepBudgetPerPlayer(), loaded.deepActiveTargetPerPlayer(), loaded.packetIntervalTicks(),
                    loaded.maximizeDirections(), loaded.warningSounds(), List.of(new EcologyDefinition.Entry(
                    new net.minecraft.resources.ResourceLocation("minecraft", "zombie"),
                    EcologyDefinition.Role.COMMON, 0.0, 1, 1, 1, 1)));
            SpawnLocator.SpawnResult result = SpawnLocator.spawnAt(helper.getLevel(), List.of(player),
                    new EcologyRegistry.Blend(zombieOnly, null, 0.0), 1.0, RandomSource.create(71L),
                    helper.absolutePos(SPAWN), true, 8);
            SpawnLocator.SpawnResult second = SpawnLocator.spawnAt(helper.getLevel(), List.of(player),
                    new EcologyRegistry.Blend(zombieOnly, null, 0.0), 1.0, RandomSource.create(73L),
                    helper.absolutePos(SECOND_SPAWN), true, 8);
            helper.assertTrue(result.spawned(), "EVENT-authored spawn must succeed in valid geometry");
            helper.assertTrue(second.spawned(), "a second Director group member must materialize independently");
            helper.assertTrue(result.mob() != null && result.mob().getType() == EntityType.ZOMBIE,
                    "the authored test roster must produce a real zombie");
            helper.assertTrue(result.mob() != null && result.mob().getTarget() == player,
                    "spawn must target the nearest eligible player");
            helper.assertTrue(result.mob() != null && !result.mob().isNoAi() && !result.mob().isInvulnerable(),
                    "materialized Director mobs must be damageable and have ordinary AI");
            helper.assertTrue(result.mob() != null && result.mob().getTags().contains(SpawnLocator.PROVENANCE_TAG),
                    "spawn must carry the provenance scoreboard tag");
            helper.assertTrue(result.mob() != null
                            && result.mob().getPersistentData().getBoolean(SpawnLocator.PROVENANCE_NBT),
                    "spawn must carry the persistent provenance marker");
            helper.assertTrue(result.mob() != null
                            && result.mob().getPersistentData().hasUUID(SpawnLocator.TARGET_NBT),
                    "spawn must persist its assigned pursuit target for reload restoration");
            if (result.mob() != null) {
                result.mob().setTarget(null);
                result.mob().setNoAi(true);
                result.mob().setInvulnerable(true);
                SpawnLocator.restoreDirectorMob(result.mob());
            }
            helper.assertTrue(result.mob() != null && result.mob().getTarget() == player
                            && !result.mob().isNoAi() && !result.mob().isInvulnerable(),
                    "provenance restoration must recover targetable, damageable AI after a reload-like reset");
            helper.assertTrue(second.mob() != null && second.mob().getTarget() == player
                            && !second.mob().isNoAi() && !second.mob().isInvulnerable(),
                    "other materialized group members must retain independent combat state");
            if (second.mob() != null) {
                second.mob().getPersistentData().remove(SpawnLocator.PROVENANCE_NBT);
                second.mob().setTarget(null);
                second.mob().setNoAi(true);
                second.mob().setInvulnerable(true);
                SpawnLocator.restoreDirectorMob(second.mob());
            }
            helper.assertTrue(second.mob() != null
                            && second.mob().getPersistentData().getBoolean(SpawnLocator.PROVENANCE_NBT)
                            && second.mob().getTarget() == player
                            && !second.mob().isNoAi() && !second.mob().isInvulnerable(),
                    "a loaded legacy provenance tag must migrate and recover ordinary combat state");
            if (result.mob() != null) result.mob().discard();
            if (second.mob() != null) second.mob().discard();
            finish(helper, player);
        });
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = TEMPLATE, timeoutTicks = 800)
    public static void authoredDirectorMobCanKillFullHealthSurvivalPlayer(GameTestHelper helper) {
        buildFixture(helper);
        ServerPlayer player = registeredPlayer(helper, PLAYER);
        helper.runAfterDelay(10, () -> {
            EcologyDefinition loaded = EcologyRegistry.INSTANCE.definitions().get(id("sculk"));
            helper.assertTrue(loaded != null, "sculk ecology must be loaded");
            EcologyDefinition.Entry spider = loaded == null ? null : loaded.roster().stream()
                    .filter(entry -> entry.entity().equals(EntityType.getKey(EntityType.SPIDER)))
                    .findFirst().orElse(null);
            helper.assertTrue(spider != null, "the authored sculk ecology must contain its real spider entry");
            EcologyDefinition spiderOnly = new EcologyDefinition(loaded.id(), loaded.noiseScale(),
                    loaded.cadenceMinimumSeconds(), loaded.cadenceMaximumSeconds(), loaded.warningMinimumSeconds(),
                    loaded.warningMaximumSeconds(), loaded.surgeSeconds(), loaded.recoverySeconds(),
                    loaded.deepBudgetPerPlayer(), loaded.deepActiveTargetPerPlayer(), loaded.packetIntervalTicks(),
                    loaded.maximizeDirections(), loaded.warningSounds(), List.of(spider));
            SpawnLocator.SpawnResult result = SpawnLocator.spawnAt(helper.getLevel(), List.of(player),
                    new EcologyRegistry.Blend(spiderOnly, null, 0.0), 1.0, RandomSource.create(72L),
                    helper.absolutePos(SPAWN), true, 8);
            helper.assertTrue(result.spawned(), "authored Director spider must EVENT-spawn in valid wave geometry");
            helper.assertTrue(result.mob() != null && result.mob().getType() == EntityType.SPIDER,
                    "the real authored sculk entry must produce a spider");
            helper.assertTrue(result.mob() != null && result.mob().getTarget() == player,
                    "the wave mob must begin with the registered player as its target");
            helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                    "the registered combat target must be in survival mode");
            helper.assertTrue(!player.isInvulnerable() && player.getArmorValue() == 0,
                    "the registered combat target must be ordinarily vulnerable and unarmored");
            helper.assertTrue(player.getHealth() == player.getMaxHealth(),
                    "the registered combat target must begin at full health");

            if (result.mob() != null) {
                result.mob().setTarget(null);
                result.mob().setNoAi(true);
                result.mob().setInvulnerable(true);
                SpawnLocator.restoreDirectorMob(result.mob());
            }
            helper.assertTrue(result.mob() != null && result.mob().getTarget() == player
                            && !result.mob().isNoAi() && !result.mob().isInvulnerable(),
                    "the real authored combat mob must recover targetable, damageable AI after reload restoration");

            long combatStarted = helper.getTick();
            helper.succeedWhen(() -> {
                helper.assertTrue(!player.isAlive(), "authored Director spider has not killed the player; health="
                        + player.getHealth() + " target=" + (result.mob() == null ? "missing" : result.mob().getTarget()));
                helper.assertTrue(player.getLastDamageSource() != null
                                && player.getLastDamageSource().getEntity() == result.mob(),
                        "the authored Director spider must be the player's lethal damage source");
                DepthDirectorMod.LOGGER.info(
                        "Director lethal-wave qualification passed: minecraft:spider killed a full-health unarmored survival player in {} ticks",
                        helper.getTick() - combatStarted);
                if (result.mob() != null) result.mob().discard();
                helper.getLevel().getServer().getPlayerList().remove(player);
                buildFixture(helper);
            });
        });
    }

    private static SpawnLocator.Rejection rejection(GameTestHelper helper, ServerPlayer player) {
        Mob zombie = zombie(helper);
        SpawnLocator.Rejection rejection = SpawnLocator.inspectCandidate(helper.getLevel(), List.of(player),
                helper.absolutePos(SPAWN), zombie).rejection();
        zombie.discard();
        return rejection;
    }

    private static Mob zombie(GameTestHelper helper) {
        Mob zombie = EntityType.ZOMBIE.create(helper.getLevel());
        if (zombie == null) helper.fail("could not create zombie probe");
        return zombie;
    }

    private static String pathSummary(Mob zombie, ServerPlayer player) {
        Path path = zombie.getNavigation().createPath(player.blockPosition(), 0);
        String positions = " start=" + zombie.blockPosition().toShortString()
                + " target=" + player.blockPosition().toShortString();
        return path == null ? "path=null" + positions : "pathReach=" + path.canReach() + " nodes=" + path.getNodeCount()
                + " end=" + (path.getEndNode() == null ? "null" : path.getEndNode().asBlockPos().toShortString())
                + positions;
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos relativePosition) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes((relativePosition.toShortString() + helper.getTick()).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)), "director-test-player"));
        Vec3 position = helper.absoluteVec(Vec3.atBottomCenterOf(relativePosition));
        player.setPos(position.x, position.y, position.z);
        return player;
    }

    private static ServerPlayer registeredPlayer(GameTestHelper helper, BlockPos relativePosition) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "director-lethal"));
        Vec3 position = helper.absoluteVec(Vec3.atBottomCenterOf(relativePosition));
        player.setPos(position.x, position.y, position.z);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player);
        player.setGameMode(GameType.SURVIVAL);
        player.setInvulnerable(false);
        player.setHealth(player.getMaxHealth());
        player.setPos(position.x, position.y, position.z);
        player.setDeltaMovement(Vec3.ZERO);
        return player;
    }

    private static void buildFixture(GameTestHelper helper) {
        for (int x = 0; x < 15; x++) {
            for (int y = 0; y < 6; y++) {
                for (int z = 0; z < 15; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        for (int x = 0; x < 15; x++) {
            for (int z = 0; z < 15; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            }
        }
        for (int y = 1; y < 5; y++) {
            for (int edge = 0; edge < 15; edge++) {
                helper.setBlock(new BlockPos(0, y, edge), Blocks.STONE);
                helper.setBlock(new BlockPos(14, y, edge), Blocks.STONE);
                helper.setBlock(new BlockPos(edge, y, 0), Blocks.STONE);
                helper.setBlock(new BlockPos(edge, y, 14), Blocks.STONE);
            }
        }
        for (int z = 1; z < 12; z++) {
            for (int y = 1; y < 5; y++) helper.setBlock(new BlockPos(7, y, z), Blocks.STONE);
        }
    }

    private static void finish(GameTestHelper helper, ServerPlayer... players) {
        buildFixture(helper);
        helper.succeed();
    }

    private static net.minecraft.resources.ResourceLocation id(String path) {
        return new net.minecraft.resources.ResourceLocation(DepthDirectorMod.MOD_ID, path);
    }

    private record SavedBlock(BlockPos position, BlockState state) {}
}
