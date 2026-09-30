package com.bettercontent.bettercaveencounters;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Bounded pressure for open waterways, independent of underground cave encounters. */
final class WaterwayDirector {
    static final WaterwayDirector INSTANCE = new WaterwayDirector();
    private static final int WARNING_SECONDS = 5;
    private static final int MAX_LOCAL_THREATS = 8;
    private static final String WATERWAY_MOB = "WaterwayDirector";
    private final RandomSource random = RandomSource.create();

    private WaterwayDirector() {}

    void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (now % 20L != 0L) return;
        WaterwayRegistry.Definition definition = WaterwayRegistry.INSTANCE.overworld();
        if (definition == null) return;
        DirectorSavedData data = DirectorSavedData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            DirectorSavedData.Track track = data.track(player.getUUID());
            boolean exposed = exposed(player);
            if (!exposed) {
                track.aquaticProgress(Math.max(0, track.aquaticProgress() - (now % 100L == 0L ? 1 : 0)));
                continue;
            }
            if (track.aquaticCadence() == 0) track.aquaticCadence(rollCadence(definition));
            track.aquaticProgress(Math.min(track.aquaticCadence(), track.aquaticProgress() + 1));
            if (track.aquaticProgress() == track.aquaticCadence() - WARNING_SECONDS)
                player.displayClientMessage(Component.literal("Something stirs in the water nearby..."), true);
            if (track.aquaticProgress() < track.aquaticCadence() || now < track.aquaticRetryAt()) continue;
            if (localThreats(player) >= MAX_LOCAL_THREATS
                    || DirectorRuntime.INSTANCE.activeDirectorMobCount() >= DirectorConfig.GLOBAL_DIRECTOR_CAP.get()) {
                track.aquaticRetryAt(now + 200L);
                continue;
            }
            if (spawnPacket(player, definition)) {
                track.aquaticProgress(0);
                track.aquaticCadence(rollCadence(definition));
                track.aquaticRetryAt(0L);
            } else {
                track.aquaticRetryAt(now + 600L);
            }
        }
    }

    static boolean exposed(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.serverLevel().dimension() != Level.OVERWORLD) return false;
        GameType mode = player.gameMode.getGameModeForPlayer();
        if (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE) return false;
        if (player.isInWaterOrBubble()) return player.serverLevel().getFluidState(player.blockPosition()).is(FluidTags.WATER)
                && player.serverLevel().getFluidState(player.blockPosition().below()).is(FluidTags.WATER);
        if (!(player.getVehicle() instanceof Boat)) return false;
        BlockPos below = player.blockPosition().below(2);
        return player.serverLevel().getFluidState(below).is(FluidTags.WATER);
    }

    private int rollCadence(WaterwayRegistry.Definition definition) {
        return definition.minimumSeconds() + random.nextInt(definition.maximumSeconds() - definition.minimumSeconds() + 1);
    }

    private static int localThreats(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(Mob.class, new AABB(player.blockPosition()).inflate(64),
                mob -> mob.getPersistentData().getBoolean(WATERWAY_MOB)).size();
    }

    private boolean spawnPacket(ServerPlayer player, WaterwayRegistry.Definition definition) {
        List<WaterwayRegistry.Entry> available = new ArrayList<>();
        for (WaterwayRegistry.Entry entry : definition.roster()) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entry.entity());
            if (type != null) available.add(entry);
        }
        if (available.isEmpty()) return false;
        int totalWeight = available.stream().mapToInt(WaterwayRegistry.Entry::weight).sum();
        int roll = random.nextInt(totalWeight);
        WaterwayRegistry.Entry chosen = available.get(0);
        for (WaterwayRegistry.Entry entry : available) {
            roll -= entry.weight();
            if (roll < 0) { chosen = entry; break; }
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(chosen.entity());
        if (type == null) return false;
        int spawned = 0;
        for (int index = 0; index < chosen.count(); index++) if (spawnOne(player, type)) spawned++;
        return spawned > 0;
    }

    private boolean spawnOne(ServerPlayer player, EntityType<?> type) {
        ServerLevel level = player.serverLevel();
        Mob mob = type.create(level) instanceof Mob created ? created : null;
        if (mob == null) return false;
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 18.0 + random.nextDouble() * 14.0;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            int y = player.blockPosition().getY() + random.nextInt(9) - 5;
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos) || !level.getFluidState(pos).is(FluidTags.WATER)
                    || !level.getFluidState(pos.above()).is(FluidTags.WATER)) continue;
            mob.moveTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360.0F, 0.0F);
            if (!level.noCollision(mob) || !level.getWorldBorder().isWithinBounds(mob.getBoundingBox())) continue;
            ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            mob.addTag(SpawnLocator.PROVENANCE_TAG);
            mob.getPersistentData().putBoolean(SpawnLocator.PROVENANCE_NBT, true);
            mob.getPersistentData().putBoolean(WATERWAY_MOB, true);
            mob.getPersistentData().putUUID(SpawnLocator.TARGET_NBT, player.getUUID());
            mob.setTarget(player);
            if (level.addFreshEntity(mob)) return true;
            break;
        }
        mob.discard();
        return false;
    }
}
