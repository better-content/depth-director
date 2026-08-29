package com.bettercontent.depthdirector;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DepthDirectorMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DepthDirectorGameTests {
    private DepthDirectorGameTests() {}

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void depthCurveIsBounded(GameTestHelper helper) {
        helper.assertTrue(DepthMath.depthFactor(63, 63, -64) == 0.0, "sea level must be inactive");
        helper.assertTrue(DepthMath.depthFactor(-64, 63, -64) == 1.0, "world floor must be full pressure");
        helper.assertTrue(DepthMath.depthFactor(0, 63, -64) > 0.45, "deepslate transition must carry material pressure");
        helper.succeed();
    }

    @GameTest(templateNamespace = DepthDirectorMod.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void ecologyNoiseIsStable(GameTestHelper helper) {
        var id = new net.minecraft.resources.ResourceLocation("depth_director", "undead");
        double first = EcologyNoise.sample(42L, id, 100.0, -80.0, 768.0);
        double second = EcologyNoise.sample(42L, id, 100.0, -80.0, 768.0);
        helper.assertTrue(first == second && first >= 0.0 && first <= 1.0, "noise must be deterministic and normalized");
        helper.succeed();
    }
}
