package com.bettercontent.depthdirector;

public final class DepthMath {
    private DepthMath() {}

    public static int controlCeiling(int surfaceY, int reserveDepth) {
        return surfaceY - Math.max(0, reserveDepth);
    }

    public static boolean isControlled(int y, int controlCeiling) {
        return y < controlCeiling;
    }

    public static double depthFactor(int y, int controlCeiling, int minimumY) {
        if (!isControlled(y, controlCeiling) || minimumY >= controlCeiling) return 0.0;
        double raw = clamp((double) (controlCeiling - y) / (double) (controlCeiling - minimumY), 0.0, 1.0);
        return raw * raw * (3.0 - 2.0 * raw);
    }

    public static double lerp(double from, double to, double amount) {
        return from + (to - from) * clamp(amount, 0.0, 1.0);
    }

    public static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
