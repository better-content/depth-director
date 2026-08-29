package com.bettercontent.depthdirector;

import net.minecraftforge.common.ForgeConfigSpec;

public final class DirectorConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue GROUP_RADIUS;
    public static final ForgeConfigSpec.IntValue SPAWN_MINIMUM_RADIUS;
    public static final ForgeConfigSpec.IntValue SPAWN_MAXIMUM_RADIUS;
    public static final ForgeConfigSpec.IntValue BLOCK_LIGHT_LIMIT;
    public static final ForgeConfigSpec.IntValue GLOBAL_DIRECTOR_CAP;
    public static final ForgeConfigSpec.IntValue MAX_SPAWNS_PER_TICK;
    public static final ForgeConfigSpec.IntValue MAX_SPAWNS_PER_SECOND;
    public static final ForgeConfigSpec.IntValue SURFACE_RESERVE_DEPTH;
    public static final ForgeConfigSpec.IntValue SURFACE_DECAY_SECONDS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Small, server-authoritative underground encounter Director.").push("director");
        ENABLED = builder.define("enabled", true);
        GROUP_RADIUS = builder.defineInRange("groupRadius", 64, 16, 192);
        SPAWN_MINIMUM_RADIUS = builder.defineInRange("spawnMinimumRadius", 28, 24, 96);
        SPAWN_MAXIMUM_RADIUS = builder.defineInRange("spawnMaximumRadius", 64, 32, 128);
        BLOCK_LIGHT_LIMIT = builder.defineInRange("spawnBlockLightLimit", 2, 0, 15);
        GLOBAL_DIRECTOR_CAP = builder.defineInRange("globalDirectorMobCap", 160, 1, 512);
        MAX_SPAWNS_PER_TICK = builder.defineInRange("maximumSpawnsPerTick", 8, 1, 32);
        MAX_SPAWNS_PER_SECOND = builder.defineInRange("maximumSpawnsPerSecond", 24, 1, 128);
        SURFACE_RESERVE_DEPTH = builder.comment(
                        "Number of blocks below the local leaf-ignoring terrain surface reserved before Director control begins.",
                        "Keep this aligned with better_content_fixes mobs.naturalSurfaceDepth; the Better Content pack uses 6.")
                .defineInRange("surfaceReserveDepth", 6, 0, 64);
        SURFACE_DECAY_SECONDS = builder.comment(
                        "Seconds outside the Director-controlled depth range required to drain full pressure.")
                .defineInRange("surfacePressureDecaySeconds", 480, 30, 3600);
        builder.pop();
        SPEC = builder.build();
    }

    private DirectorConfig() {}
}
