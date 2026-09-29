package com.nstut.explosion.terrain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SphereShellCursorTest {
    @Test
    void everyIntegerOffsetBelongsToExactlyOneCeilingDistanceShell() {
        for (int x = -12; x <= 12; x++) {
            for (int y = -12; y <= 12; y++) {
                for (int z = -12; z <= 12; z++) {
                    int expected = (int) Math.ceil(Math.sqrt((double)x * x + (double)y * y + (double)z * z));
                    int matches = 0;
                    for (int r = 0; r <= expected + 1; r++) {
                        if (SphereShellCursor.isInShell(x, y, z, r)) matches++;
                    }
                    assertEquals(1, matches, "offset " + x + "," + y + "," + z);
                    assertTrue(SphereShellCursor.isInShell(x, y, z, expected));
                }
            }
        }
    }

    @Test
    void shellColumnExtentsExactlyMatchShellPredicate() {
        for (int radius = 0; radius <= 16; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    int outer = SphereShellCursor.outerZExtent(x, y, radius);
                    int inner = SphereShellCursor.innerZExtent(x, y, radius);
                    for (int z = -radius; z <= radius; z++) {
                        boolean generated = outer >= 0 && Math.abs(z) <= outer && (inner < 0 || Math.abs(z) > inner);
                        assertEquals(SphereShellCursor.isInShell(x, y, z, radius), generated,
                            "r=" + radius + " offset=" + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    @Test
    void shellExcludesInnerAndOuterPoints() {
        assertFalse(SphereShellCursor.isInShell(1, 0, 0, 3));
        assertTrue(SphereShellCursor.isInShell(3, 0, 0, 3));
        assertFalse(SphereShellCursor.isInShell(4, 0, 0, 3));
    }

    @Test void resumableCursorVisitsSphereExactlyOnceInsideOut() {
        var cursor=SphereShellCursor.start(0);
        var seen=new java.util.HashSet<String>();
        int previous=0;
        while(cursor.radius()<=12) {
            assertTrue(cursor.radius()>=previous);
            previous=cursor.radius();
            assertTrue(SphereShellCursor.isInShell(cursor.x(),cursor.y(),cursor.z(),cursor.radius()));
            assertTrue(seen.add(cursor.x()+","+cursor.y()+","+cursor.z()));
            cursor.advance();
            cursor=new SphereShellCursor(cursor.radius(),cursor.x(),cursor.y(),cursor.z());
        }
        int expected=0;
        for(int x=-12;x<=12;x++) for(int y=-12;y<=12;y++) for(int z=-12;z<=12;z++)
            if(x*x+y*y+z*z<=144) expected++;
        assertEquals(expected,seen.size());
    }
}
