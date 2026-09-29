package com.nstut.explosion.terrain;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SphereBoundaryCursorTest {
    private record Point(int x, int y, int z) {}
    private static boolean inside(int x, int y, int z, int r) { return x*x+y*y+z*z<=r*r; }
    private static Set<Point> collect(SphereBoundaryCursor cursor) {
        Set<Point> found = new HashSet<>();
        while (!cursor.done()) {
            if (cursor.valid()) assertTrue(found.add(new Point(cursor.x(),cursor.y(),cursor.z())), "Duplicate boundary cell");
            cursor.advance();
        }
        return found;
    }
    @Test void matchesSixNeighborOracleWithoutDuplicates() {
        for (int r : new int[]{0,1,4,16}) {
            Set<Point> expected = new HashSet<>();
            for(int x=-r-1;x<=r+1;x++) for(int y=-r-1;y<=r+1;y++) for(int z=-r-1;z<=r+1;z++) {
                if(!inside(x,y,z,r) && (inside(x-1,y,z,r)||inside(x+1,y,z,r)||inside(x,y-1,z,r)
                        ||inside(x,y+1,z,r)||inside(x,y,z-1,r)||inside(x,y,z+1,r))) expected.add(new Point(x,y,z));
            }
            assertEquals(expected,collect(new SphereBoundaryCursor(r,0)), "radius="+r);
        }
    }
    @Test void resumesAtEveryIndexIncludingFaceBoundaries() {
        var scan = new SphereBoundaryCursor(4,0);
        while(!scan.done()) {
            var copy = new SphereBoundaryCursor(4,scan.index());
            assertEquals(scan.valid(),copy.valid());
            assertEquals(scan.x(),copy.x()); assertEquals(scan.y(),copy.y()); assertEquals(scan.z(),copy.z());
            scan.advance();
        }
        assertTrue(new SphereBoundaryCursor(4,Long.MAX_VALUE).done());
    }
    @Test void maximumPowerTraversalIsQuadraticAndEveryResultTouchesSphere() {
        var scan = new SphereBoundaryCursor(128,0);
        assertEquals(6L*257*257,scan.size());
        for(Point p:collect(scan)) {
            assertFalse(inside(p.x,p.y,p.z,128));
            assertTrue(inside(p.x-1,p.y,p.z,128)||inside(p.x+1,p.y,p.z,128)||inside(p.x,p.y-1,p.z,128)
                    ||inside(p.x,p.y+1,p.z,128)||inside(p.x,p.y,p.z-1,128)||inside(p.x,p.y,p.z+1,128));
        }
    }
}
