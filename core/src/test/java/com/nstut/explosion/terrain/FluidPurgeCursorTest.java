package com.nstut.explosion.terrain;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class FluidPurgeCursorTest {
    @Test void traversesEachSphereCellExactlyOnceInGravityOrder() {
        for(int radius=0;radius<=12;radius++) {
            FluidPurgeCursor cursor=new FluidPurgeCursor(radius,0);
            var visited=new HashSet<String>();
            int previousY=radius, expected=0;
            while(!cursor.done()) {
                assertTrue(cursor.y()<=previousY);
                previousY=cursor.y();
                if(cursor.inside()) assertTrue(visited.add(cursor.x()+","+cursor.y()+","+cursor.z()));
                cursor.advance();
                // Simulate save/reload repeatedly, including between fluid removal batches.
                if(cursor.index()%97==0) cursor=new FluidPurgeCursor(radius,cursor.index());
            }
            for(int x=-radius;x<=radius;x++) for(int y=-radius;y<=radius;y++) for(int z=-radius;z<=radius;z++)
                if(x*x+y*y+z*z<=radius*radius) expected++;
            assertEquals(expected,visited.size());
        }
    }
}
