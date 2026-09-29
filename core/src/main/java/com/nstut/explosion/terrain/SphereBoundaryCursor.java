package com.nstut.explosion.terrain;

/** Resumable, duplicate-free six-neighbor boundary of the integer sphere; O(radius squared). */
public final class SphereBoundaryCursor {
    private final int radius, width;
    private final long faceSize, size;
    private long index;
    public SphereBoundaryCursor(int radius, long index) {
        if (radius < 0 || radius > 128) throw new IllegalArgumentException("Invalid radius");
        this.radius = radius; this.width = radius * 2 + 1;
        this.faceSize = (long) width * width; this.size = 6 * faceSize;
        this.index = Math.max(0, Math.min(index, size));
    }
    public long index() { return index; }
    public long size() { return size; }
    public boolean done() { return index >= size; }
    public void advance() { index++; }
    private int axis() { return (int) (index / faceSize) / 2; }
    private int a() { return (int) ((index % faceSize) / width) - radius; }
    private int b() { return (int) (index % width) - radius; }
    private int extent() {
        long n = (long) radius * radius - (long) a() * a() - (long) b() * b();
        return n < 0 ? 0 : ((int) Math.sqrt(n) + 1) * ((index / faceSize) % 2 == 0 ? -1 : 1);
    }
    public int x() { return axis() == 0 ? extent() : a(); }
    public int y() { return axis() == 1 ? extent() : axis() == 0 ? a() : b(); }
    public int z() { return axis() == 2 ? extent() : b(); }
    private boolean inward(int x, int y, int z, int axis) {
        if (axis == 0) { if (x == 0) return false; x -= Integer.signum(x); }
        if (axis == 1) { if (y == 0) return false; y -= Integer.signum(y); }
        if (axis == 2) { if (z == 0) return false; z -= Integer.signum(z); }
        return (long) x*x + (long) y*y + (long) z*z <= (long) radius*radius;
    }
    public boolean valid() {
        if (done() || (long) a()*a() + (long) b()*b() > (long) radius*radius) return false;
        int x = x(), y = y(), z = z();
        // A boundary cell can touch the sphere on several axes. The first axis owns it.
        for (int previous = 0; previous < axis(); previous++) if (inward(x, y, z, previous)) return false;
        return true;
    }
}
