package com.bettercontent.depthdirector;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;

/** Typed diagnostic surface used by the separate real-catalogue qualification module. */
public final class DepthDirectorDiagnostics {
    public static final String PROVENANCE_TAG = SpawnLocator.PROVENANCE_TAG;
    public static final String PROVENANCE_NBT = SpawnLocator.PROVENANCE_NBT;

    private DepthDirectorDiagnostics() {}

    public static void reset(long seed) {
        DirectorRuntime.INSTANCE.reset(seed);
    }

    public static void reset() {
        DirectorRuntime.INSTANCE.reset();
    }

    public static String inspect(ServerPlayer player) {
        return DirectorRuntime.INSTANCE.inspect(player);
    }

    public static void playerDied(MinecraftServer server, UUID player) {
        DirectorRuntime.INSTANCE.playerDied(server, player);
    }

    public static void removeMob(UUID mob) {
        DirectorRuntime.INSTANCE.removeMob(mob);
    }

    public static double cadenceSeconds(int minimum, int maximum, double jitter) {
        return DirectorPolicy.cadenceSeconds(minimum, maximum, jitter);
    }

    public static SpawnOutcome spawnAt(ServerLevel level, List<ServerPlayer> players,
                                       EcologyRegistry.Blend blend, double depth, RandomSource random,
                                       BlockPos position, boolean allowHeavy, int maximumCost) {
        SpawnLocator.SpawnResult result = SpawnLocator.spawnAt(
                level, players, blend, depth, random, position, allowHeavy, maximumCost);
        return new SpawnOutcome(result.spawned(), result.mob(), result.entity());
    }

    public static String diagnose(ServerLevel level, List<ServerPlayer> players,
                                  ResourceLocation entity, BlockPos position) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entity);
        Mob mob = type == null ? null : type.create(level) instanceof Mob created ? created : null;
        if (mob == null) return "entity does not create a Mob";
        try {
            SpawnLocator.ensureApproachRange(mob);
            SpawnLocator.CandidateValidation validation = SpawnLocator.inspectCandidate(level, players, position, mob);
            if (validation.rejection() != SpawnLocator.Rejection.NONE) {
                return "candidate rejection=" + validation.rejection();
            }
            mob.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0F, 0.0F);
            boolean eventPosition = SpawnLocator.eventSpawnPositionAllowed(mob, level);
            return "candidate accepted, eventPosition=" + eventPosition
                    + ", collisionFree=" + level.noCollision(mob)
                    + ", inBorder=" + level.getWorldBorder().isWithinBounds(mob.getBoundingBox());
        } finally {
            mob.discard();
        }
    }

    public record SpawnOutcome(boolean spawned, Mob mob, ResourceLocation entity) {}
}
