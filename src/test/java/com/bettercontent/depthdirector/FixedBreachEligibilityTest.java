package com.bettercontent.depthdirector;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedBreachEligibilityTest {
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");
    private static final ResourceLocation END = new ResourceLocation("minecraft", "the_end");
    private static final ResourceLocation OTHER_NON_NATURAL = new ResourceLocation("example", "pocket");
    private static final ResourceLocation PINNED_DEEP_VOID = new ResourceLocation("the_deep_void", "deep_void");
    private static final ResourceLocation DEEP_VOID_ECOLOGY = new ResourceLocation("depth_director", "deep_void");

    @Test
    void selectorUsesThePinnedDeepVoidDimensionId() {
        assertEquals(PINNED_DEEP_VOID, FixedBreachEligibility.DEEP_VOID);
    }

    @Test
    void naturalDimensionsKeepExistingEligibilityWithoutAuthorization() {
        assertTrue(FixedBreachEligibility.allows(true, OVERWORLD, false));
    }

    @Test
    void deepVoidRequiresExplicitFixedBreachAuthorization() {
        assertFalse(FixedBreachEligibility.allows(false, PINNED_DEEP_VOID, false));
        assertTrue(FixedBreachEligibility.allows(false, PINNED_DEEP_VOID, true));
    }

    @Test
    void authorizationCannotAdmitOtherNonNaturalDimensions() {
        assertFalse(FixedBreachEligibility.allows(false, NETHER, true));
        assertFalse(FixedBreachEligibility.allows(false, OTHER_NON_NATURAL, true));
    }

    @Test
    void nativeEntryAuthorizationAllowsOverworldAndEndOnlyIntoPinnedDeepVoid() {
        assertTrue(FixedBreachEligibility.authorizesNativeEntry(OVERWORLD, PINNED_DEEP_VOID));
        assertTrue(FixedBreachEligibility.authorizesNativeEntry(END, PINNED_DEEP_VOID));
        assertFalse(FixedBreachEligibility.authorizesNativeEntry(NETHER, PINNED_DEEP_VOID));
        assertFalse(FixedBreachEligibility.authorizesNativeEntry(OTHER_NON_NATURAL, PINNED_DEEP_VOID));
        assertFalse(FixedBreachEligibility.authorizesNativeEntry(OVERWORLD, OTHER_NON_NATURAL));
    }

    @Test
    void fixedBreachEcologyIsSelectedOnlyForAuthorizedExactNonNaturalDeepVoid() {
        assertEquals(DEEP_VOID_ECOLOGY,
                FixedBreachEligibility.fixedBreachEcologyId(false, PINNED_DEEP_VOID, true));
        assertEquals(null, FixedBreachEligibility.fixedBreachEcologyId(false, PINNED_DEEP_VOID, false));
        assertEquals(null, FixedBreachEligibility.fixedBreachEcologyId(true, PINNED_DEEP_VOID, true));
        assertEquals(null, FixedBreachEligibility.fixedBreachEcologyId(false, OTHER_NON_NATURAL, true));
        assertEquals(null, FixedBreachEligibility.fixedBreachEcologyId(false, NETHER, true));
    }
}
