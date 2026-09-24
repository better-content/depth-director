package com.bettercontent.depthdirector;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class OptionalEcologyEntityTest {
    @Test
    void missingOptionalRosterEntitiesProduceNoSelectionInsteadOfAnotherMob() {
        EcologyDefinition definition = EcologyDefinition.parse(
                new ResourceLocation("depth_director", "test_optional"),
                JsonParser.parseString("""
                        {
                          "cadence_seconds": {"minimum": 240, "maximum": 420},
                          "warning_seconds": {"minimum": 10, "maximum": 24},
                          "surge_seconds": 90,
                          "recovery_seconds": 90,
                          "deep_budget_per_player": 64,
                          "deep_active_target_per_player": 36,
                          "packet_interval_ticks": 100,
                          "roster": [
                            {"entity": "the_deep_void:bone_crawler", "role": "swarm"},
                            {"entity": "the_deep_void:mourner", "role": "common"}
                          ]
                        }
                        """).getAsJsonObject());

        assertNull(definition.pick(RandomSource.create(19L), 1.0, true, ignored -> false));
    }
}
