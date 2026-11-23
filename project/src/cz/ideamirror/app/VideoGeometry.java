package cz.ideamirror.app;

/** Encoder frame size helper (tested in CoreTest; Windows uses the identical formula). */
final class VideoGeometry {
    private VideoGeometry() {}

    /**
     * Scales the source so the longer side is at most maxSide and rounds each side to the NEAREST multiple
     * of 16 (hardware encoders need 16-alignment). Rounding to nearest instead of down keeps the aspect
     * ratio, e.g. 1366×768 → 1280×720 instead of the previous 1280×704.
     */
    static int[] fit(int rawW, int rawH, int maxSide) {
        rawW = Math.max(16, rawW);
        rawH = Math.max(16, rawH);
        double scale = Math.min(1.0, (double) maxSide / Math.max(rawW, rawH));
        int w = round16(rawW * scale), h = round16(rawH * scale);
        while (Math.max(w, h) > Math.max(16, (maxSide / 16) * 16)) {
            if (w >= h) w -= 16; else h -= 16;
        }
        return new int[]{Math.max(16, w), Math.max(16, h)};
    }

    static int round16(double v) {
        return Math.max(16, (int) Math.round(v / 16.0) * 16);
    }
}
