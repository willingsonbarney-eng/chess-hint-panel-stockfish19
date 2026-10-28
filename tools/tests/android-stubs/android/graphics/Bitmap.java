package android.graphics;

/** Minimal host-test stand-in for android.graphics.Bitmap; not shipped in the APK. */
public class Bitmap {
    private final int width, height;
    private final int[] pixels;
    public Bitmap(int width, int height, int[] pixels) {
        this.width = width; this.height = height; this.pixels = pixels;
    }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void getPixels(int[] dst, int offset, int stride, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) System.arraycopy(pixels, (y + row) * width + x, dst, offset + row * stride, w);
    }
    public static Bitmap createScaledBitmap(Bitmap src, int width, int height, boolean filter) {
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int sx = Math.min(src.width - 1, x * src.width / width);
            int sy = Math.min(src.height - 1, y * src.height / height);
            out[y * width + x] = src.pixels[sy * src.width + sx];
        }
        return new Bitmap(width, height, out);
    }
    public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height) {
        int[] out = new int[width * height];
        for (int row = 0; row < height; row++) System.arraycopy(src.pixels, (y + row) * src.width + x, out, row * width, width);
        return new Bitmap(width, height, out);
    }
    public void recycle() { }
}
