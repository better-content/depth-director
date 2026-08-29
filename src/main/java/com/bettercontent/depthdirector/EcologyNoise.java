package com.bettercontent.depthdirector;

import net.minecraft.resources.ResourceLocation;

public final class EcologyNoise {
    private EcologyNoise() {}

    public static double sample(long worldSeed, ResourceLocation ecology, double x, double z, double scale) {
        double safeScale = Math.max(64.0, scale);
        double gx = x / safeScale;
        double gz = z / safeScale;
        long x0 = (long) Math.floor(gx);
        long z0 = (long) Math.floor(gz);
        double tx = smooth(gx - x0);
        double tz = smooth(gz - z0);
        long salt = mix64(worldSeed ^ ecology.toString().hashCode());
        double a = unit(salt, x0, z0);
        double b = unit(salt, x0 + 1, z0);
        double c = unit(salt, x0, z0 + 1);
        double d = unit(salt, x0 + 1, z0 + 1);
        return DepthMath.lerp(DepthMath.lerp(a, b, tx), DepthMath.lerp(c, d, tx), tz);
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double unit(long salt, long x, long z) {
        long mixed = mix64(salt ^ mix64(x * 0x9E3779B97F4A7C15L) ^ mix64(z * 0xC2B2AE3D27D4EB4FL));
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
