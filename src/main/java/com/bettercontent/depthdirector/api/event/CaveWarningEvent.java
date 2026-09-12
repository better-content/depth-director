package com.bettercontent.depthdirector.api.event;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;
import java.util.UUID;
/** A committed warning packet delivered to an actual encounter participant. */
public final class CaveWarningEvent extends Event {
    public final ServerPlayer player; public final UUID encounter; public final BlockPos approach;
    public CaveWarningEvent(ServerPlayer player,UUID encounter,BlockPos approach){this.player=player;this.encounter=encounter;this.approach=approach.immutable();}
}
