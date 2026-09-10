package com.bettercontent.depthdirector.worldtest;

import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(DepthDirectorWorldTestMod.MOD_ID)
public final class DepthDirectorWorldTestMod {
    public static final String MOD_ID = "depth_director_world_tests";

    public DepthDirectorWorldTestMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::registerGameTests);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        event.register(DepthDirectorWorldGameTests.class);
    }
}
