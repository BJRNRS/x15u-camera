package app.x15u.camera;

/**
 * Ein Farblook: wenige, gut verstaendliche Stellgroessen statt einer undurchsichtigen Tabelle.
 * Alle Looks sind eigene Abstimmungen. Sie lehnen sich an klassische Kamera-Charaktere an,
 * sind aber keine Kopien geschuetzter Herstellerprofile.
 */
final class Look {

    static final String NATURAL = "Natuerlich";
    static final String COLOR = "Farbe";
    static final String FILM = "Film";
    static final String MONO = "Schwarzweiss";
    static final String[] CATEGORIES = {NATURAL, COLOR, FILM, MONO};

    final String category;
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

    Look(String category, String name) {
        this.category = category;
        this.name = name;
    }

    /** Dateiname-taugliche Form des Namens. */
    String fileTag() {
        return name.replaceAll("[^A-Za-z0-9]+", "-");
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
        Look standard = new Look(NATURAL, "Standard");
        standard.identity = true;
        return new Look[]{
                // --- Natuerlich
                standard,
                // Zurueckhaltend und ehrlich: etwas weniger Saettigung, sanfter Kontrast, leichte Randabdunklung.
                new Look(NATURAL, "Natur").sat(0.92f).contrast(0.18f).warmth(0.03f).vignette(0.12f),
                // Neutral, weich, offene Schatten, saubere Hauttoene.
                new Look(NATURAL, "Nordisch").sat(0.96f).contrast(-0.10f).fade(0.02f, 1f).warmth(-0.02f)
                        .highlights(0.010f, 0.006f, 0f),
                // Flach und hell, viel Zeichnung in Schatten und Lichtern.
                new Look(NATURAL, "Weich").sat(0.94f).contrast(-0.22f).fade(0.03f, 0.99f),
                // Warme, gedaempfte Hauttoene, sanfter Kontrast.
                new Look(NATURAL, "Portraet").sat(0.88f).contrast(0.08f).warmth(0.05f).tint(0.02f)
                        .highlights(0.012f, 0.004f, -0.004f).vignette(0.10f),

                // --- Farbe
                // Kraeftige Farben, knackiger Kontrast.
                new Look(COLOR, "Lebendig").sat(1.28f).contrast(0.30f),
                // Tiefe, satte Farben mit dunklen Schatten.
                new Look(COLOR, "Satt").sat(1.40f).contrast(0.45f).vignette(0.12f),
                // Warm und farbig.
                new Look(COLOR, "Sommer").sat(1.18f).contrast(0.20f).warmth(0.12f)
                        .highlights(0.020f, 0.008f, -0.010f),
                // Petrol in den Schatten, warme Lichter.
                new Look(COLOR, "Kino").sat(1.05f).contrast(0.32f)
                        .shadows(-0.025f, 0.006f, 0.030f).highlights(0.028f, 0.010f, -0.020f).vignette(0.18f),
                // Hell, matt, zarte Farben.
                new Look(COLOR, "Pastell").sat(0.72f).contrast(-0.12f).fade(0.08f, 1f).warmth(0.02f)
                        .highlights(0.008f, 0.004f, 0.010f),

                // --- Film
                // Gedeckte Farben, harter Kontrast, kuehle Schatten.
                new Look(FILM, "Chrom").sat(0.74f).contrast(0.42f).shadows(-0.012f, 0f, 0.022f).vignette(0.15f),
                // Warm, matt, mit Korn.
                new Look(FILM, "Warmfilm").sat(0.90f).contrast(0.12f).fade(0.05f, 0.97f).warmth(0.10f)
                        .shadows(0f, 0.010f, 0.016f).highlights(0.022f, 0.010f, -0.012f)
                        .grain(0.35f).vignette(0.14f),
                // Kuehl, leicht gruenliche Schatten, matter Abschluss.
                new Look(FILM, "Kuehlfilm").sat(0.86f).contrast(0.16f).fade(0.035f, 0.98f).warmth(-0.07f)
                        .shadows(-0.010f, 0.012f, 0.012f).grain(0.25f),
                // Diafilm: satt, kontrastreich, leicht magentafarbene Schatten.
                new Look(FILM, "Dia").sat(1.22f).contrast(0.50f).shadows(0.010f, -0.008f, 0.016f)
                        .grain(0.20f).vignette(0.16f),
                // Negativfilm: weiche Kurve, angehobenes Schwarz, gruenliche Schatten.
                new Look(FILM, "Negativ").sat(0.92f).contrast(0.05f).fade(0.06f, 0.96f).warmth(0.04f)
                        .shadows(-0.006f, 0.014f, 0.004f).highlights(0.014f, 0.008f, -0.006f).grain(0.30f),
                // Ausgeblichener Abzug: wenig Farbe, warm, sehr matt.
                new Look(FILM, "Vintage").sat(0.62f).contrast(-0.05f).fade(0.10f, 0.92f).warmth(0.12f)
                        .highlights(0.018f, 0.010f, -0.016f).grain(0.40f).vignette(0.22f),
                // Kuehle Nacht: blaue Schatten, kraeftiger Kontrast.
                new Look(FILM, "Nacht").sat(0.82f).contrast(0.38f).warmth(-0.10f)
                        .shadows(-0.016f, 0.000f, 0.032f).grain(0.30f).vignette(0.20f),

                // --- Schwarzweiss
                // Ausgewogen.
                new Look(MONO, "Mono").mono(0.30f, 0.59f, 0.11f).contrast(0.22f),
                // Weich, grau, viel Zeichnung.
                new Look(MONO, "Mono weich").mono(0.30f, 0.59f, 0.11f).contrast(-0.15f).fade(0.04f, 0.97f),
                // Wie mit Rotfilter: dunkler Himmel, harter Kontrast, Korn.
                new Look(MONO, "Mono hart").mono(0.55f, 0.38f, 0.07f).contrast(0.62f).grain(0.45f).vignette(0.22f),
                // Warm getont wie ein Selenabzug.
                new Look(MONO, "Selen").mono(0.33f, 0.56f, 0.11f).contrast(0.28f)
                        .shadows(0.010f, 0.002f, -0.010f).highlights(0.016f, 0.008f, -0.010f).grain(0.20f),
                // Kalt getont.
                new Look(MONO, "Blauton").mono(0.30f, 0.59f, 0.11f).contrast(0.25f)
                        .shadows(-0.012f, -0.002f, 0.020f).highlights(-0.004f, 0f, 0.008f),
        };
    }
}
