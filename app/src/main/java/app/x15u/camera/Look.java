package app.x15u.camera;

/**
 * Ein Farblook: wenige, gut verstaendliche Stellgroessen statt einer undurchsichtigen Tabelle.
 * Alle Looks sind eigene Abstimmungen. Sie lehnen sich an klassische Kamera-Charaktere an,
 * sind aber keine Kopien geschuetzter Herstellerprofile.
 */
final class Look {

    final String name;
    boolean identity;

    /** 1 = unveraendert, 0 = farblos. */
    float saturation = 1f;
    /** 0 = unveraendert, positiv = S-Kurve, negativ = flacher. */
    float contrast = 0f;
    /** Angehobenes Schwarz (0..0.15) fuer einen matten Filmlook. */
    float blackLift = 0f;
    /** Gedeckeltes Weiss (0.9..1). */
    float whiteCap = 1f;
    /** Positiv = waermer, negativ = kuehler. */
    float warmth = 0f;
    /** Positiv = Richtung Magenta, negativ = Richtung Gruen. */
    float tint = 0f;

    boolean mono;
    float monoR = 0.299f;
    float monoG = 0.587f;
    float monoB = 0.114f;

    /** Farbstich in den Schatten bzw. Lichtern, je Kanal (R, G, B), etwa -0.05..0.05. */
    final float[] shadowTint = {0f, 0f, 0f};
    final float[] highlightTint = {0f, 0f, 0f};

    /** Randabdunklung 0..0.5. */
    float vignette = 0f;
    /** Korn 0..1. */
    float grain = 0f;

    Look(String name) {
        this.name = name;
    }

    Look sat(float v) {
        saturation = v;
        return this;
    }

    Look contrast(float v) {
        contrast = v;
        return this;
    }

    Look fade(float black, float white) {
        blackLift = black;
        whiteCap = white;
        return this;
    }

    Look warmth(float v) {
        warmth = v;
        return this;
    }

    Look tint(float v) {
        tint = v;
        return this;
    }

    Look mono(float r, float g, float b) {
        mono = true;
        monoR = r;
        monoG = g;
        monoB = b;
        return this;
    }

    Look shadows(float r, float g, float b) {
        shadowTint[0] = r;
        shadowTint[1] = g;
        shadowTint[2] = b;
        return this;
    }

    Look highlights(float r, float g, float b) {
        highlightTint[0] = r;
        highlightTint[1] = g;
        highlightTint[2] = b;
        return this;
    }

    Look vignette(float v) {
        vignette = v;
        return this;
    }

    Look grain(float v) {
        grain = v;
        return this;
    }

    static Look[] all() {
        Look standard = new Look("Standard");
        standard.identity = true;
        return new Look[]{
                standard,
                // Zurueckhaltend und ehrlich: etwas weniger Saettigung, sanfter Kontrast, leichte Randabdunklung.
                new Look("Natur").sat(0.92f).contrast(0.18f).warmth(0.03f).vignette(0.12f),
                // Kraeftige Farben, knackiger Kontrast.
                new Look("Lebendig").sat(1.28f).contrast(0.30f),
                // Neutral, weich, offene Schatten, saubere Hauttoene.
                new Look("Nordisch").sat(0.96f).contrast(-0.10f).fade(0.02f, 1f).warmth(-0.02f)
                        .highlights(0.010f, 0.006f, 0f),
                // Gedeckte Farben, harter Kontrast, kuehle Schatten.
                new Look("Chrom").sat(0.74f).contrast(0.42f).shadows(-0.012f, 0f, 0.022f).vignette(0.15f),
                // Warm, matt, mit Korn.
                new Look("Warmfilm").sat(0.90f).contrast(0.12f).fade(0.05f, 0.97f).warmth(0.10f)
                        .shadows(0f, 0.010f, 0.016f).highlights(0.022f, 0.010f, -0.012f)
                        .grain(0.35f).vignette(0.14f),
                // Kuehl, leicht gruenliche Schatten, matter Abschluss.
                new Look("Kuehlfilm").sat(0.86f).contrast(0.16f).fade(0.035f, 0.98f).warmth(-0.07f)
                        .shadows(-0.010f, 0.012f, 0.012f).grain(0.25f),
                // Schwarzweiss, ausgewogen.
                new Look("Mono").mono(0.30f, 0.59f, 0.11f).contrast(0.22f),
                // Schwarzweiss wie mit Rotfilter: dunkler Himmel, harter Kontrast, Korn.
                new Look("Mono hart").mono(0.55f, 0.38f, 0.07f).contrast(0.62f).grain(0.45f).vignette(0.22f),
        };
    }
}
