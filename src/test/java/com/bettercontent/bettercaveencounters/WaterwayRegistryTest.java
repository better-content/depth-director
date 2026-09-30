package com.bettercontent.bettercaveencounters;

import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WaterwayRegistryTest {
    @Test
    void cadenceAndOptionalRosterLoadFromDatapack() {
        var json = JsonParser.parseString("""
                {"minimum_seconds":240,"maximum_seconds":420,"roster":
                  [{"entity":"example:water_threat","weight":3,"count":2}]}
                """).getAsJsonObject();
        var definition = WaterwayRegistry.parse(json);
        assertEquals(240, definition.minimumSeconds());
        assertEquals(420, definition.maximumSeconds());
        assertEquals("example:water_threat", definition.roster().get(0).entity().toString());
        assertThrows(IllegalArgumentException.class, () -> WaterwayRegistry.parse(JsonParser.parseString("""
                {"minimum_seconds":420,"maximum_seconds":240,"roster":
                  [{"entity":"example:water_threat","weight":3,"count":2}]}
                """).getAsJsonObject()));
    }

    @Test
    void waterwayCountdownSurvivesSaveAndReload() {
        UUID player = UUID.randomUUID();
        DirectorSavedData saved = new DirectorSavedData();
        var track = saved.track(player);
        track.aquaticProgress(137);
        track.aquaticCadence(366);
        track.aquaticRetryAt(12345L);
        var restored = DirectorSavedData.load(saved.save(new CompoundTag())).peekTrack(player);
        assertNotNull(restored);
        assertEquals(137, restored.aquaticProgress());
        assertEquals(366, restored.aquaticCadence());
        assertEquals(12345L, restored.aquaticRetryAt());
    }
}
