package com.bettercontent.bettercaveencounters.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/** The first positive pressure increment of an underground buildup. */
public final class CavePressureStartedEvent extends Event {
    public final ServerPlayer player;
    public final long tick;

    public CavePressureStartedEvent(ServerPlayer player, long tick) {
        this.player = player;
        this.tick = tick;
    }
}
