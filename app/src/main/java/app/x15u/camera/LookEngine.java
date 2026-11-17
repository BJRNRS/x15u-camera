package app.x15u.camera;

import android.graphics.Bitmap;
import android.graphics.ColorMatrix;

/** Wendet einen Look auf ein fertiges Bild an und liefert eine Naeherung fuer die Live-Vorschau. */
final class LookEngine {

    private LookEngine() {
    }

    /** 3x3-Farbmatrix (zeilenweise) aus Saettigung, Schwarzweiss-Mischung und Farbtemperatur. */
    private static float[] matrix(Look look) {
        float[] m = new float[9];
        if (look.mono) {
            for (int row = 0; row < 3; row++) {
                m[row * 3] = look.monoR;
                m[row * 3 + 1] = look.monoG;
                m[row * 3 + 2] = look.monoB;
            }
            return m;
        }
        float[] luma = {0.299f, 0.587f, 0.114f};
        float[] gain = {
                1f + 0.18f * look.warmth + 0.04f * look.tint,
                1f - 0.08f * look.tint,
                1f - 0.18f * look.warmth + 0.04f * look.tint,
        };
        float s = look.saturation;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float v = (1f - s) * luma[col] + (row == col ? s : 0f);
                m[row * 3 + col] = v * gain[col];
            }
        }
        return m;
    }

    /** Tonwertkurve je Kanal als Tabelle mit 256 Eintraegen. */
    private static int[] curve(Look look, int channel) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float x = i / 255f;
            float y;
            if (look.contrast >= 0f) {
                float smooth = x * x * (3f - 2f * x);
                y = x + look.contrast * (smooth - x);
            } else {
                y = 0.5f + (x - 0.5f) * (1f + look.contrast * 0.6f);
            }
            y = look.blackLift + y * (look.whiteCap - look.blackLift);
            float shadow = (1f - y) * (1f - y);
            float highlight = y * y;
            y += look.shadowTint[channel] * shadow * 4f + look.highlightTint[channel] * highlight * 4f;
            int v = Math.round(y * 255f);
            lut[i] = v < 0 ? 0 : Math.min(v, 255);
        }
        return lut;
    }

    /** Veraendert das Bild direkt. Das Bitmap muss veraenderbar sein (ARGB_8888). */
    static void apply(Bitmap bitmap, Look look) {
        if (look == null || look.identity) return;
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();

        float[] mf = matrix(look);
        final int[] m = new int[9];
        for (int i = 0; i < 9; i++) m[i] = Math.round(mf[i] * 1024f);
        final int[] lutR = curve(look, 0);
        final int[] lutG = curve(look, 1);
        final int[] lutB = curve(look, 2);

        final boolean vignette = look.vignette > 0f;
        final int[] vignetteLut = new int[256];
        for (int i = 0; i < 256; i++) {
            double t = i / 255.0;
            vignetteLut[i] = (int) Math.round(1024.0 * (1.0 - look.vignette * Math.pow(t, 1.6)));
        }
        final int cx = w / 2;
        final int cy = h / 2;
        final long maxD2 = Math.max(1L, (long) cx * cx + (long) cy * cy);

        final int grain = Math.round(look.grain * 56f);
        int seed = 0x2545F491;

        final int rows = 64;
        final int[] buf = new int[w * rows];
        for (int y0 = 0; y0 < h; y0 += rows) {
            int n = Math.min(rows, h - y0);
            bitmap.getPixels(buf, 0, w, 0, y0, w, n);
            for (int yy = 0; yy < n; yy++) {
                int dy = y0 + yy - cy;
                long dy2 = (long) dy * dy;
                int off = yy * w;
                for (int x = 0; x < w; x++) {
                    int p = buf[off + x];
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;

                    int r2 = (m[0] * r + m[1] * g + m[2] * b) >> 10;
                    int g2 = (m[3] * r + m[4] * g + m[5] * b) >> 10;
                    int b2 = (m[6] * r + m[7] * g + m[8] * b) >> 10;

                    if (vignette) {
                        int dx = x - cx;
                        int idx = (int) (((long) dx * dx + dy2) * 255L / maxD2);
                        if (idx > 255) idx = 255;
                        int f = vignetteLut[idx];
                        r2 = (r2 * f) >> 10;
                        g2 = (g2 * f) >> 10;
                        b2 = (b2 * f) >> 10;
                    }

                    r2 = lutR[r2 < 0 ? 0 : (r2 > 255 ? 255 : r2)];
                    g2 = lutG[g2 < 0 ? 0 : (g2 > 255 ? 255 : g2)];
                    b2 = lutB[b2 < 0 ? 0 : (b2 > 255 ? 255 : b2)];

                    if (grain > 0) {
                        seed ^= seed << 13;
                        seed ^= seed >>> 17;
                        seed ^= seed << 5;
                        int noise = (((seed & 0xFF) - 128) * grain) >> 8;
                        r2 += noise;
                        g2 += noise;
                        b2 += noise;
                        r2 = r2 < 0 ? 0 : (r2 > 255 ? 255 : r2);
                        g2 = g2 < 0 ? 0 : (g2 > 255 ? 255 : g2);
                        b2 = b2 < 0 ? 0 : (b2 > 255 ? 255 : b2);
                    }

                    buf[off + x] = 0xFF000000 | (r2 << 16) | (g2 << 8) | b2;
                }
            }
            bitmap.setPixels(buf, 0, w, 0, y0, w, n);
        }
    }

    /**
     * Naeherung des Looks fuer die Live-Vorschau: Farbe und Grundkontrast stimmen,
     * Kurvenform, Korn und Randabdunklung zeigt erst das gespeicherte Bild.
     */
    static ColorMatrix previewMatrix(Look look) {
        float[] m = matrix(look);
        float k = 1f + 0.55f * look.contrast;
        float range = look.whiteCap - look.blackLift;
        float scale = k * range;
        float offset = 255f * (look.blackLift + range * 0.5f * (1f - k));
        float[] c = new float[20];
        for (int row = 0; row < 3; row++) {
            c[row * 5] = m[row * 3] * scale;
            c[row * 5 + 1] = m[row * 3 + 1] * scale;
            c[row * 5 + 2] = m[row * 3 + 2] * scale;
            c[row * 5 + 3] = 0f;
            c[row * 5 + 4] = offset + 255f * (look.highlightTint[row] - look.shadowTint[row]) * 0.5f;
        }
        c[18] = 1f;
        return new ColorMatrix(c);
    }
}
