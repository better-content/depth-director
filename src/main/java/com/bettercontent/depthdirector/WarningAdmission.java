package com.bettercontent.depthdirector;

import com.bettercontent.bettercontentfixes.compat.sleeping.SleepDangerInterruption;
import com.bettercontent.depthdirector.api.event.CaveWarningEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;

import java.util.UUID;

/** Publishes only warnings that have reached a participant, then admits their immediate sleep risk. */
final class WarningAdmission {
    private WarningAdmission() {
    }

    static void publish(final ServerPlayer player, final UUID encounter, final BlockPos approach) {
        publish(player, encounter, approach,
                event -> MinecraftForge.EVENT_BUS.post(event),
                (target, reason) -> SleepDangerInterruption.interrupt(target, reason));
    }

    static void publish(final ServerPlayer player, final UUID encounter, final BlockPos approach,
                        final WarningPublisher warningPublisher, final SleepInterrupter sleepInterrupter) {
        warningPublisher.publish(new CaveWarningEvent(player, encounter, approach));
        sleepInterrupter.interrupt(player, SleepDangerInterruption.Reason.DIRECTOR_WARNING);
    }

    @FunctionalInterface
    interface WarningPublisher {
        void publish(CaveWarningEvent event);
    }

    @FunctionalInterface
    interface SleepInterrupter {
        void interrupt(ServerPlayer player, SleepDangerInterruption.Reason reason);
    }
}
