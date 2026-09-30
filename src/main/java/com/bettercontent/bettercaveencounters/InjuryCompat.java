package com.bettercontent.bettercaveencounters;

import com.bettercontent.betterdeathsdoor.api.InjuryApi;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

final class InjuryCompat {
    private InjuryCompat() {}

    static int activeMaimCount(Player player) {
        return ModList.get().isLoaded("better_deaths_door") ? InjuryApi.activeMaimCount(player) : 0;
    }

    static float semanticHealth(Player player) {
        return ModList.get().isLoaded("better_deaths_door") ? InjuryApi.semanticHealth(player) : player.getHealth();
    }
}
