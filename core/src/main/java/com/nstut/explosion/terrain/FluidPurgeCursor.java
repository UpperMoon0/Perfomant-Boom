package com.nstut.explosion.terrain;

/** Bounded resumable top-down traversal. Never removes fluid outside the impact sphere. */
public final class FluidPurgeCursor {
    private final int radius;
    private final long width;
    private long index;
    public FluidPurgeCursor(int radius, long index) {
        if (radius < 0 || radius > 128) throw new IllegalArgumentException("Invalid radius");
        this.radius=radius;
        this.width=2L*radius+1;
        this.index=Math.max(0, Math.min(index, width*width*width));
    }
    public long index() { return index; }
    public boolean done() { return index >= width*width*width; }
    public int x() { return (int)((index/width)%width)-radius; }
    public int y() { return radius-(int)(index/(width*width)); }
    public int z() { return (int)(index%width)-radius; }
    public boolean inside() {
        return (long)x()*x()+(long)y()*y()+(long)z()*z() <= (long)radius*radius;
    }
    public void advance() { index++; }
}
