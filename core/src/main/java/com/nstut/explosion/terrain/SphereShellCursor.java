package com.nstut.explosion.terrain;

/** Inside-out integer sphere traversal; snapshot fields preserve existing saved progress. */
public final class SphereShellCursor {
    private int radius, x, y, z;
    public SphereShellCursor(int radius, int x, int y, int z) {
        if (radius < 0 || radius > 129) throw new IllegalArgumentException("Invalid radius");
        this.radius=radius; this.x=x; this.y=y; this.z=z;
    }
    public static SphereShellCursor start(int radius) {
        var cursor = new SphereShellCursor(radius, -radius, -radius-1, 0);
        cursor.nextColumn();
        return cursor;
    }
    public int radius() { return radius; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public void advance() {
        int outer=outerZExtent(x,y,radius), inner=innerZExtent(x,y,radius);
        if (inner>=0 && z==-inner-1) { z=inner+1; return; }
        if (z<outer) { z++; return; }
        nextColumn();
    }
    private void nextColumn() {
        while (true) {
            if (++y>radius) { y=-radius; x++; }
            if (x>radius) { radius++; x=-radius; y=-radius-1; continue; }
            int outer=outerZExtent(x,y,radius), inner=innerZExtent(x,y,radius);
            if (outer>inner) { z=-outer; return; }
        }
    }
    /** Returns true when an integer block offset belongs to the radius-r spherical shell. */
    public static boolean isInShell(int dx, int dy, int dz, int radius) {
        if (radius < 0) return false;
        long distSq = (long) dx * dx + (long) dy * dy + (long) dz * dz;
        long outerSq = (long) radius * radius;
        if (distSq > outerSq) return false;
        if (radius == 0) return true;
        long inner = radius - 1L;
        return distSq > inner * inner;
    }

    /** Maximum |z| inside the radius-r sphere for one x/y column, or -1 outside the sphere. */
    public static int outerZExtent(int dx, int dy, int radius) {
        if (radius < 0) return -1;
        long remaining = (long) radius * radius - (long) dx * dx - (long) dy * dy;
        return remaining < 0L ? -1 : floorSqrt(remaining);
    }

    /** Maximum |z| inside the previous (r-1) sphere for one x/y column, or -1 if none. */
    public static int innerZExtent(int dx, int dy, int radius) {
        if (radius <= 0) return -1;
        int innerRadius = radius - 1;
        long remaining = (long) innerRadius * innerRadius - (long) dx * dx - (long) dy * dy;
        return remaining < 0L ? -1 : floorSqrt(remaining);
    }

    private static int floorSqrt(long value) {
        int root=(int)Math.sqrt(value);
        while ((long)(root+1)*(root+1)<=value) root++;
        while ((long)root*root>value) root--;
        return root;
    }
}
