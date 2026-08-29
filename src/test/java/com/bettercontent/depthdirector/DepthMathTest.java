package com.bettercontent.depthdirector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepthMathTest {
    @Test
    void depthCurveHasStableEndpointsAndMonotonicInterior() {
        assertEquals(0.0, DepthMath.depthFactor(63, 63, -64));
        assertEquals(1.0, DepthMath.depthFactor(-64, 63, -64));
        double shallow = DepthMath.depthFactor(40, 63, -64);
        double middle = DepthMath.depthFactor(0, 63, -64);
        double deep = DepthMath.depthFactor(-32, 63, -64);
        assertTrue(shallow < middle && middle < deep);
    }

    @Test
    void aboveSeaAndInvalidWorldsAreInactive() {
        assertEquals(0.0, DepthMath.depthFactor(100, 63, -64));
        assertEquals(0.0, DepthMath.depthFactor(0, 0, 0));
    }
}
