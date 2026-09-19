package com.bettercontent.depthdirector;

import com.bettercontent.bettercontentfixes.compat.sleeping.SleepDangerInterruption;
import com.bettercontent.depthdirector.api.event.CaveWarningEvent;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class WarningAdmissionTest {
    @Test
    void committedWarningPublishesItsExistingEventThenAdmitsDirectorSleepDanger() {
        final UUID encounter = UUID.randomUUID();
        final BlockPos approach = new BlockPos(24, -31, 8);
        final AtomicInteger published = new AtomicInteger();
        final AtomicInteger interrupted = new AtomicInteger();
        final AtomicReference<CaveWarningEvent> event = new AtomicReference<>();
        final AtomicReference<SleepDangerInterruption.Reason> reason = new AtomicReference<>();

        WarningAdmission.publish(null, encounter, approach,
                warning -> {
                    published.incrementAndGet();
                    event.set(warning);
                },
                (player, admittedReason) -> {
                    assertEquals(1, published.get(), "the warning must remain visible before sleep changes");
                    interrupted.incrementAndGet();
                    reason.set(admittedReason);
                });

        assertEquals(1, published.get());
        assertEquals(1, interrupted.get());
        assertSame(encounter, event.get().encounter);
        assertEquals(approach, event.get().approach);
        assertEquals(SleepDangerInterruption.Reason.DIRECTOR_WARNING, reason.get());
    }
}
