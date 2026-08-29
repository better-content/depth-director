package com.bettercontent.depthdirector;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(DepthDirectorMod.MOD_ID)
public final class DepthDirectorMod {
    public static final String MOD_ID = "depth_director";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DepthDirectorMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerGameTests);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DirectorConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(DepthDirectorEvents.class);
        LOGGER.info("Loaded {}", MOD_ID);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        event.register(DepthDirectorGameTests.class);
    }
}
