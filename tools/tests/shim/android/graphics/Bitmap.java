package android.graphics;

/** Host-JVM stand-in for android.graphics.Bitmap: just enough for Vision to compile. Tests use the int[] entry points. */
public class Bitmap {
    private final int w, h;
    private final int[] px;
    private Bitmap(int w, int h, int[] px) { this.w = w; this.h = h; this.px = px; }
    public static Bitmap fromPixels(int w, int h, int[] px) { return new Bitmap(w, h, px); }
    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public void getPixels(int[] out, int offset, int stride, int x, int y, int width, int height) {
        for (int j = 0; j < height; j++)
            for (int i = 0; i < width; i++) out[offset + j * stride + i] = px[(y + j) * w + (x + i)];
    }
    public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height) {
        int[] o = new int[width * height];
        src.getPixels(o, 0, width, x, y, width, height);
        return new Bitmap(width, height, o);
    }
    public static Bitmap createScaledBitmap(Bitmap src, int dw, int dh, boolean filter) {
        int[] o = new int[dw * dh];
        for (int j = 0; j < dh; j++)
            for (int i = 0; i < dw; i++)
                o[j * dw + i] = src.px[Math.min(src.h - 1, j * src.h / dh) * src.w + Math.min(src.w - 1, i * src.w / dw)];
        return new Bitmap(dw, dh, o);
    }
    public void recycle() { }
}
