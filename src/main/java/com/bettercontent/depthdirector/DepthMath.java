package com.bettercontent.depthdirector;

public final class DepthMath {
    private DepthMath() {}

    public static double depthFactor(int y, int seaLevel, int minimumY) {
        if (y >= seaLevel || minimumY >= seaLevel) return 0.0;
        double raw = clamp((double) (seaLevel - y) / (double) (seaLevel - minimumY), 0.0, 1.0);
        return raw * raw * (3.0 - 2.0 * raw);
    }

    public static double lerp(double from, double to, double amount) {
        return from + (to - from) * clamp(amount, 0.0, 1.0);
    }

    public static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
