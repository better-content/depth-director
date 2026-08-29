package com.bettercontent.depthdirector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class SpawnLocator {
    static final String PROVENANCE_TAG = "depth_director_spawned";
    static final String PROVENANCE_NBT = "DepthDirectorSpawned";
    static final TagKey<EntityType<?>> DENIED = TagKey.create(Registries.ENTITY_TYPE,
            new ResourceLocation(DepthDirectorMod.MOD_ID, "denied"));

    private SpawnLocator() {}

    static boolean hasApproach(ServerLevel level, List<ServerPlayer> players, RandomSource random) {
        return approach(level, players, random).isPresent();
    }

    static Optional<BlockPos> approach(ServerLevel level, List<ServerPlayer> players, RandomSource random) {
        if (players.isEmpty()) return Optional.empty();
        Mob probe = EntityType.ZOMBIE.create(level);
        if (probe == null) return Optional.empty();
        Candidate candidate = candidate(level, players, players.get(random.nextInt(players.size())).position(), random, -1, probe);
        probe.discard();
        return candidate == null ? Optional.empty() : Optional.of(candidate.position);
    }

    static SpawnResult spawn(ServerLevel level, List<ServerPlayer> players, EcologyRegistry.Blend blend,
                             double depth, RandomSource random, int sector, boolean allowHeavy) {
        if (players.isEmpty()) return SpawnResult.failed();
        ServerPlayer anchorPlayer = players.get(random.nextInt(players.size()));
        Selection selection = blend == null ? nativeSelection(level, anchorPlayer.blockPosition(), random)
                : authoredSelection(blend, depth, random, allowHeavy);
        if (selection == null || selection.type.is(DENIED)) return SpawnResult.failed();
        Mob mob = selection.type.create(level) instanceof Mob created ? created : null;
        if (mob == null) return SpawnResult.failed();
        Candidate candidate = candidate(level, players, anchorPlayer.position(), random, sector, mob);
        if (candidate == null) {
            mob.discard();
            return SpawnResult.failed();
        }
        mob.moveTo(candidate.position.getX() + 0.5, candidate.position.getY(), candidate.position.getZ() + 0.5,
                random.nextFloat() * 360.0F, 0.0F);
        if (!ForgeEventFactory.checkSpawnPosition(mob, level, MobSpawnType.EVENT)
                || !level.noCollision(mob) || !level.getWorldBorder().isWithinBounds(mob.getBoundingBox())) {
            mob.discard();
            return SpawnResult.failed();
        }
        ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(candidate.position),
                MobSpawnType.EVENT, null, null);
        mob.addTag(PROVENANCE_TAG);
        mob.getPersistentData().putBoolean(PROVENANCE_NBT, true);
        mob.setTarget(candidate.target);
        if (!level.addFreshEntity(mob)) {
            mob.discard();
            return SpawnResult.failed();
        }
        return new SpawnResult(true, mob, selection.cost, selection.role);
    }

    private static Selection authoredSelection(EcologyRegistry.Blend blend, double depth, RandomSource random,
                                               boolean allowHeavy) {
        for (int attempt = 0; attempt < 8; attempt++) {
            EcologyDefinition ecology = blend.choose(random);
            EcologyDefinition.Entry entry = ecology.pick(random, depth);
            if (entry == null) continue;
            if (!allowHeavy && entry.role() == EcologyDefinition.Role.HEAVY) continue;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entry.entity());
            if (type != null && type.getCategory() == MobCategory.MONSTER) {
                return new Selection(type, entry.cost(), entry.role());
            }
        }
        return null;
    }

    private static Selection nativeSelection(ServerLevel level, BlockPos position, RandomSource random) {
        Optional<MobSpawnSettings.SpawnerData> selected = level.getBiome(position).value().getMobSettings()
                .getMobs(MobCategory.MONSTER).getRandom(random);
        if (selected.isEmpty()) return null;
        EntityType<?> type = selected.get().type;
        if (type.is(DENIED)) return null;
        Mob preview = type.create(level) instanceof Mob created ? created : null;
        if (preview == null) return null;
        double health = preview.getMaxHealth();
        double armor = preview.getArmorValue();
        preview.discard();
        if (health > 80.0) return null;
        int cost = Math.max(1, Math.min(8, (int) Math.ceil(health / 20.0) + (int) Math.floor(armor / 10.0)));
        return new Selection(type, cost, EcologyDefinition.Role.LINE);
    }

    private static Candidate candidate(ServerLevel level, List<ServerPlayer> players, Vec3 anchor,
                                       RandomSource random, int sector, Mob mob) {
        int minimum = DirectorConfig.SPAWN_MINIMUM_RADIUS.get();
        int maximum = Math.max(minimum, DirectorConfig.SPAWN_MAXIMUM_RADIUS.get());
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = sector >= 0
                    ? (sector + random.nextDouble()) * Math.PI / 4.0
                    : random.nextDouble() * Math.PI * 2.0;
            double radius = minimum + random.nextDouble() * (maximum - minimum);
            int x = (int) Math.floor(anchor.x + Math.cos(angle) * radius);
            int z = (int) Math.floor(anchor.z + Math.sin(angle) * radius);
            int startY = (int) Math.floor(anchor.y) + random.nextInt(25) - 8;
            for (int offset = 0; offset < 32; offset++) {
                BlockPos position = new BlockPos(x, startY - offset, z);
                if (!level.hasChunkAt(position)) break;
                if (!isFloor(level, position)) continue;
                if (level.getBrightness(LightLayer.BLOCK, position) > DirectorConfig.BLOCK_LIGHT_LIMIT.get()) break;
                if (visibleToAny(level, position, players)) break;
                ServerPlayer target = players.stream().filter(player -> player.isAlive() && !DownedCompat.isDowned(player))
                        .min(Comparator.comparingDouble(player -> player.distanceToSqr(Vec3.atCenterOf(position))))
                        .orElse(null);
                if (target == null) return null;
                mob.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0F, 0.0F);
                if (!level.noCollision(mob)) break;
                if (mob.getNavigation().createPath(target.blockPosition(), 0) == null) break;
                return new Candidate(position.immutable(), target);
            }
        }
        return null;
    }

    private static boolean isFloor(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).getCollisionShape(level, position).isEmpty()
                && level.getFluidState(position).isEmpty()
                && level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty()
                && level.getFluidState(position.above()).isEmpty()
                && level.getBlockState(position.below()).isFaceSturdy(level, position.below(), net.minecraft.core.Direction.UP);
    }

    private static boolean visibleToAny(ServerLevel level, BlockPos position, List<ServerPlayer> players) {
        Vec3 destination = Vec3.atCenterOf(position).add(0.0, 0.5, 0.0);
        for (ServerPlayer player : players) {
            if (player.distanceToSqr(destination) > 128.0 * 128.0) continue;
            HitResult result = level.clip(new ClipContext(player.getEyePosition(), destination,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (result.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    private record Candidate(BlockPos position, ServerPlayer target) {}
    private record Selection(EntityType<?> type, int cost, EcologyDefinition.Role role) {}
    record SpawnResult(boolean spawned, Mob mob, int cost, EcologyDefinition.Role role) {
        static SpawnResult failed() { return new SpawnResult(false, null, 0, null); }
    }
}
