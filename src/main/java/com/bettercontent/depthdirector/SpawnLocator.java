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
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

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
                             double depth, RandomSource random, int sector, boolean allowHeavy, int maximumCost) {
        return spawn(level, players, blend, depth, random, sector, allowHeavy, maximumCost, ignored -> true);
    }

    static SpawnResult spawn(ServerLevel level, List<ServerPlayer> players, EcologyRegistry.Blend blend,
                             double depth, RandomSource random, int sector, boolean allowHeavy, int maximumCost,
                             Predicate<EcologyDefinition.Entry> selectable) {
        if (players.isEmpty()) return SpawnResult.failed();
        ServerPlayer anchorPlayer = players.get(random.nextInt(players.size()));
        Selection selection = blend == null ? nativeSelection(level, anchorPlayer.blockPosition(), random)
                : authoredSelection(blend, depth, random, allowHeavy, maximumCost, selectable);
        if (selection == null || selection.cost > maximumCost || selection.type.is(DENIED)) return SpawnResult.failed();
        Mob mob = selection.type.create(level) instanceof Mob created ? created : null;
        if (mob == null) return SpawnResult.failed();
        Candidate candidate = candidate(level, players, anchorPlayer.position(), random, sector, mob);
        if (candidate == null) {
            mob.discard();
            return SpawnResult.failed();
        }
        return finishSpawn(level, candidate, selection, mob, random);
    }

    static SpawnResult spawnAt(ServerLevel level, List<ServerPlayer> players, EcologyRegistry.Blend blend,
                               double depth, RandomSource random, BlockPos position, boolean allowHeavy,
                               int maximumCost) {
        if (players.isEmpty()) return SpawnResult.failed();
        Selection selection = blend == null ? nativeSelection(level, position, random)
                : authoredSelection(blend, depth, random, allowHeavy, maximumCost, ignored -> true);
        if (selection == null || selection.cost > maximumCost || selection.type.is(DENIED)) return SpawnResult.failed();
        Mob mob = selection.type.create(level) instanceof Mob created ? created : null;
        if (mob == null) return SpawnResult.failed();
        Candidate candidate = validateCandidate(level, players, position, mob).orElse(null);
        if (candidate == null) {
            mob.discard();
            return SpawnResult.failed();
        }
        return finishSpawn(level, candidate, selection, mob, random);
    }

    private static SpawnResult finishSpawn(ServerLevel level, Candidate candidate, Selection selection,
                                           Mob mob, RandomSource random) {
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
        return new SpawnResult(true, mob, selection.entity, selection.cost, selection.role);
    }

    private static Selection authoredSelection(EcologyRegistry.Blend blend, double depth, RandomSource random,
                                               boolean allowHeavy, int maximumCost,
                                               Predicate<EcologyDefinition.Entry> selectable) {
        for (int attempt = 0; attempt < 8; attempt++) {
            EcologyDefinition ecology = blend.choose(random);
            EcologyDefinition.Entry entry = ecology.pick(random, depth, allowHeavy, entity -> {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entity);
                return type != null && type.getCategory() == MobCategory.MONSTER;
            }, candidate -> candidate.cost() <= maximumCost && selectable.test(candidate));
            if (entry == null) continue;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entry.entity());
            if (type != null && type.getCategory() == MobCategory.MONSTER) {
                return new Selection(type, entry.entity(), entry.cost(), entry.role());
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
        return new Selection(type, ForgeRegistries.ENTITY_TYPES.getKey(type), cost, EcologyDefinition.Role.LINE);
    }

    private static Candidate candidate(ServerLevel level, List<ServerPlayer> players, Vec3 anchor,
                                       RandomSource random, int sector, Mob mob) {
        int minimum = DirectorConfig.SPAWN_MINIMUM_RADIUS.get();
        int maximum = Math.max(minimum, DirectorConfig.SPAWN_MAXIMUM_RADIUS.get());
        for (int attempt = 0; attempt < 48; attempt++) {
            BlockPos sample = samplePosition(anchor, random, sector, minimum, maximum);
            int x = sample.getX();
            int z = sample.getZ();
            int startY = sample.getY();
            for (int offset = 0; offset < 32; offset++) {
                BlockPos position = new BlockPos(x, startY - offset, z);
                if (!level.hasChunkAt(position)) break;
                Candidate candidate = validateCandidate(level, players, position, mob).orElse(null);
                if (candidate != null) return candidate;
                if (isFloor(level, position)) break;
            }
        }
        return null;
    }

    static BlockPos samplePosition(Vec3 anchor, RandomSource random, int sector, int minimum, int maximum) {
        int safeMaximum = Math.max(minimum, maximum);
        double angle = sector >= 0
                ? (sector + random.nextDouble()) * Math.PI / 4.0
                : random.nextDouble() * Math.PI * 2.0;
        double radius = minimum + random.nextDouble() * (safeMaximum - minimum);
        return new BlockPos((int) Math.floor(anchor.x + Math.cos(angle) * radius),
                (int) Math.floor(anchor.y) + random.nextInt(25) - 8,
                (int) Math.floor(anchor.z + Math.sin(angle) * radius));
    }

    static Optional<Candidate> validateCandidate(ServerLevel level, List<ServerPlayer> players,
                                                 BlockPos position, Mob mob) {
        return inspectCandidate(level, players, position, mob).candidate();
    }

    static CandidateValidation inspectCandidate(ServerLevel level, List<ServerPlayer> players,
                                                BlockPos position, Mob mob) {
        if (!level.hasChunkAt(position) || !isFloor(level, position)) {
            return CandidateValidation.rejected(Rejection.OCCUPIED);
        }
        int localLight = Math.max(level.getBrightness(LightLayer.BLOCK, position),
                level.getBrightness(LightLayer.SKY, position));
        if (localLight > DirectorConfig.BLOCK_LIGHT_LIMIT.get() || level.canSeeSky(position)) {
            return CandidateValidation.rejected(Rejection.LIT);
        }
        if (visibleToAny(level, position, players)) {
            return CandidateValidation.rejected(Rejection.VISIBLE);
        }
        ServerPlayer target = players.stream().filter(player -> player.isAlive() && !DownedCompat.isDowned(player))
                .min(Comparator.comparingDouble(player -> player.distanceToSqr(Vec3.atCenterOf(position))))
                .orElse(null);
        if (target == null) return CandidateValidation.rejected(Rejection.NO_TARGET);
        mob.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0F, 0.0F);
        if (!level.noCollision(mob)) return CandidateValidation.rejected(Rejection.COLLISION);
        mob.setOnGround(true);
        Path path = mob.getNavigation().createPath(target.blockPosition(), 0);
        if (path == null || !path.canReach()) return CandidateValidation.rejected(Rejection.UNREACHABLE);
        return CandidateValidation.accepted(new Candidate(position.immutable(), target));
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

    record Candidate(BlockPos position, ServerPlayer target) {}
    enum Rejection { NONE, OCCUPIED, LIT, VISIBLE, NO_TARGET, COLLISION, UNREACHABLE }
    record CandidateValidation(Optional<Candidate> candidate, Rejection rejection) {
        static CandidateValidation accepted(Candidate candidate) {
            return new CandidateValidation(Optional.of(candidate), Rejection.NONE);
        }
        static CandidateValidation rejected(Rejection rejection) {
            return new CandidateValidation(Optional.empty(), rejection);
        }
    }
    private record Selection(EntityType<?> type, ResourceLocation entity, int cost, EcologyDefinition.Role role) {}
    record SpawnResult(boolean spawned, Mob mob, ResourceLocation entity, int cost, EcologyDefinition.Role role) {
        static SpawnResult failed() { return new SpawnResult(false, null, null, 0, null); }
    }
}
