package com.bettercontent.bettercaveencounters;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcologyNoiseTest {
    @Test
    void noiseIsStableBoundedAndKeyedByEcology() {
        ResourceLocation undead = new ResourceLocation("better_cave_encounters", "undead");
        ResourceLocation sculk = new ResourceLocation("better_cave_encounters", "sculk");
        double first = EcologyNoise.sample(91L, undead, 321.5, -708.0, 768.0);
        assertEquals(first, EcologyNoise.sample(91L, undead, 321.5, -708.0, 768.0));
        assertTrue(first >= 0.0 && first <= 1.0);
        assertNotEquals(first, EcologyNoise.sample(91L, sculk, 321.5, -708.0, 768.0));
    }
}
