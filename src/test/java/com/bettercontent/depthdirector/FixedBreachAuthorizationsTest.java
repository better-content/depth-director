package com.bettercontent.depthdirector;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedBreachAuthorizationsTest {
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");
    private static final ResourceLocation END = new ResourceLocation("minecraft", "the_end");
    private static final ResourceLocation DEEP_VOID = new ResourceLocation("the_deep_void", "deep_void");
    private static final ResourceLocation OTHER = new ResourceLocation("example", "pocket");

    @Test
    void logoutClearsAuthorizationAndLoginInDeepVoidRestoresIt() {
        UUID player = UUID.randomUUID();
        FixedBreachAuthorizations authorizations = new FixedBreachAuthorizations();
        authorizations.dimensionChanged(player, OVERWORLD, DEEP_VOID);
        assertTrue(authorizations.contains(player));

        authorizations.loggedOut(player);
        assertFalse(authorizations.contains(player));
        authorizations.loggedIn(player, false, DEEP_VOID);
        assertTrue(authorizations.contains(player));
    }

    @Test
    void loginOnlyReauthorizesTheExactNonNaturalDeepVoidDimension() {
        UUID player = UUID.randomUUID();
        FixedBreachAuthorizations authorizations = new FixedBreachAuthorizations();

        authorizations.loggedIn(player, false, OTHER);
        assertFalse(authorizations.contains(player));
        authorizations.loggedIn(player, false, NETHER);
        assertFalse(authorizations.contains(player));
        authorizations.loggedIn(player, true, DEEP_VOID);
        assertFalse(authorizations.contains(player));
    }

    @Test
    void dimensionTransitionsRetainSourceRestrictionsAndClearOldAuthorization() {
        UUID player = UUID.randomUUID();
        FixedBreachAuthorizations authorizations = new FixedBreachAuthorizations();
        authorizations.dimensionChanged(player, NETHER, DEEP_VOID);
        assertFalse(authorizations.contains(player));
        authorizations.dimensionChanged(player, END, DEEP_VOID);
        assertTrue(authorizations.contains(player));
        authorizations.dimensionChanged(player, DEEP_VOID, OTHER);
        assertFalse(authorizations.contains(player));
    }
}
