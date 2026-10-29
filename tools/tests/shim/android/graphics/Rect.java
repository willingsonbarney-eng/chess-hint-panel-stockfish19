package android.graphics;

/** Host-JVM stand-in for android.graphics.Rect (tests only - never packaged into the APK). */
public class Rect {
    public int left, top, right, bottom;
    public Rect() { }
    public Rect(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public Rect(Rect o) { set(o); }
    public void set(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public void set(Rect o) { set(o.left, o.top, o.right, o.bottom); }
    public final int width() { return right - left; }
    public final int height() { return bottom - top; }
    public final int centerX() { return (left + right) >> 1; }
    public final int centerY() { return (top + bottom) >> 1; }
    public boolean contains(int x, int y) { return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom; }
    public String toShortString() { return "[" + left + "," + top + "][" + right + "," + bottom + "]"; }
    @Override public String toString() { return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")"; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof Rect)) return false;
        Rect r = (Rect) o;
        return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }
    @Override public int hashCode() { return ((left * 31 + top) * 31 + right) * 31 + bottom; }
}
