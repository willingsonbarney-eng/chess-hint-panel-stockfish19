package android.graphics;

/** Minimal host-test stand-in for android.graphics.Rect; not shipped in the APK. */
public class Rect {
    public int left, top, right, bottom;
    public Rect(int left, int top, int right, int bottom) { set(left, top, right, bottom); }
    public Rect(Rect other) { this(other.left, other.top, other.right, other.bottom); }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
    public void set(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public void set(Rect other) { set(other.left, other.top, other.right, other.bottom); }
    public boolean contains(int x, int y) { return x >= left && x < right && y >= top && y < bottom; }
    public String toShortString() { return "[" + left + "," + top + "][" + right + "," + bottom + "]"; }
}
