package com.bettercontent.depthdirector;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class DepthDirectorEvents {
    private DepthDirectorEvents() {}

    @SubscribeEvent
    public static void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(EcologyRegistry.INSTANCE);
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && DirectorConfig.ENABLED.get()) {
            DirectorRuntime.INSTANCE.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void entityJoined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob mob) {
            DirectorRuntime.INSTANCE.registerMob(mob);
        }
    }

    @SubscribeEvent
    public static void livingDied(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob) DirectorRuntime.INSTANCE.removeMob(mob.getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            DirectorRuntime.INSTANCE.playerDied(player.server, player.getUUID());
        }
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        DirectorRuntime.INSTANCE.reset();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("depthdirector").requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    context.getSource().sendSuccess(() -> Component.literal(DirectorRuntime.INSTANCE.inspect(player)), false);
                    return 1;
                }))
                .then(Commands.literal("force")
                        .then(Commands.argument("ecology", StringArgumentType.word()).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            ResourceLocation id = ResourceLocation.tryParse(StringArgumentType.getString(context, "ecology"));
                            if (id == null || !DirectorRuntime.INSTANCE.force(player, id)) {
                                context.getSource().sendFailure(Component.literal("Unknown ecology or player is not eligible"));
                                return 0;
                            }
                            context.getSource().sendSuccess(() -> Component.literal("Forced " + id), true);
                            return 1;
                        }))));
    }
}
