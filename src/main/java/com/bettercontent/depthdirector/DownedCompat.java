package com.bettercontent.depthdirector;

import com.bettercontent.downedplayerrevival.api.RevivalApi;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

final class DownedCompat {
    private DownedCompat() {}

    static boolean isDowned(Player player) {
        return ModList.get().isLoaded("downed_player_revival") && RevivalApi.isDowned(player);
    }
}
