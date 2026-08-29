package com.bettercontent.depthdirector;

import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;

final class DownedCompat {
    private static final Method IS_DOWNED = resolve();

    private DownedCompat() {}

    static boolean isDowned(Player player) {
        if (IS_DOWNED == null) return false;
        try {
            return (boolean) IS_DOWNED.invoke(null, player);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Downed Player Revival API invocation failed", exception);
        }
    }

    private static Method resolve() {
        try {
            Class<?> api = Class.forName("com.bettercontent.downedplayerrevival.api.RevivalApi");
            return api.getMethod("isDowned", Player.class);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
