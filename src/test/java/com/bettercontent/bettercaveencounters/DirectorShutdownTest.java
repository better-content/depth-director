package com.bettercontent.bettercaveencounters;

import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class DirectorShutdownTest {
    @Test void incompleteStartupDoesNotPersistWithoutAWorld() {
        DirectorRuntime.INSTANCE.reset();
        assertDoesNotThrow(() -> DepthDirectorEvents.serverStopping(new ServerStoppingEvent(null)));
        assertDoesNotThrow(() -> DepthDirectorEvents.serverStopped(new ServerStoppedEvent(null)));
    }
}
