package com.bettercontent.depthdirector;

import com.bettercontent.downedplayerrevival.api.InjuryApi;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

final class InjuryCompat {
    private InjuryCompat() {}

    static int activeMaimCount(Player player) {
        return ModList.get().isLoaded("downed_player_revival") ? InjuryApi.activeMaimCount(player) : 0;
    }

    static float semanticHealth(Player player) {
        return ModList.get().isLoaded("downed_player_revival") ? InjuryApi.semanticHealth(player) : player.getHealth();
    }
}
