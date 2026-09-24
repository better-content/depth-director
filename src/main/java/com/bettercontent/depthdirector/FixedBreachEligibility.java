package com.bettercontent.depthdirector;

import net.minecraft.resources.ResourceLocation;

/** Narrow dimension gate for encounters explicitly admitted by a fixed-breach integration. */
final class FixedBreachEligibility {
    static final ResourceLocation DEEP_VOID = new ResourceLocation("the_deep_void", "deep_void");
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation END = new ResourceLocation("minecraft", "the_end");

    private FixedBreachEligibility() {}

    static boolean allows(boolean naturalDimension, ResourceLocation dimension,
                          boolean fixedBreachAuthorized) {
        return naturalDimension || (fixedBreachAuthorized && DEEP_VOID.equals(dimension));
    }

    static boolean authorizesNativeEntry(ResourceLocation source, ResourceLocation destination) {
        return DEEP_VOID.equals(destination) && (OVERWORLD.equals(source) || END.equals(source));
    }

    static ResourceLocation fixedBreachEcologyId(boolean naturalDimension, ResourceLocation dimension,
                                                boolean fixedBreachAuthorized) {
        return !naturalDimension && fixedBreachAuthorized && DEEP_VOID.equals(dimension)
                ? new ResourceLocation("depth_director", "deep_void") : null;
    }
}
