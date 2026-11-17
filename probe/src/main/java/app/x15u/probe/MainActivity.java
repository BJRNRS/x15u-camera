package app.x15u.probe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Liest aus, was das Geraet einer Dritt-App ueber Camera2 anbietet:
 * Kameras, RAW-Groessen, Hochaufloesungsmodi, abschaltbare ISP-Stufen, Videoformate.
 * Braucht keine Berechtigung, es wird nichts aufgenommen.
 */
public class MainActivity extends Activity {

    private TextView out;
    private String report = "";
    private Uri savedUri;
    private boolean running;
    private TextureView previewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        Button copy = new Button(this);
        copy.setText("Kopieren");
        copy.setOnClickListener(v -> copyReport());

        Button share = new Button(this);
        share.setText("Teilen");
        share.setOnClickListener(v -> shareReport());

        Button test = new Button(this);
        test.setText("Aufloesungstest");
        test.setOnClickListener(v -> startExperiment());

        bar.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(share, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(test, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(11f);
        out.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        out.setPadding(pad, pad, pad, pad);
        out.setText("Lese Kameradaten ...");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(out);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Sichtbare Vorschau fuer den Aufloesungstest, wie in einer normalen Kamera-App.
        previewView = new TextureView(this);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                (int) (160 * density), (int) (120 * density));
        root.addView(previewView, previewParams);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        new Thread(() -> {
            String r;
            try {
                r = buildReport();
            } catch (Throwable t) {
                r = "Fehler beim Auslesen: " + t;
            }
            final String result = r;
            runOnUiThread(() -> {
                report = result;
                out.setText(result);
            });
        }).start();
    }

    // ---------------------------------------------------------------- Aktionen

    private void startExperiment() {
        if (running) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        runExperiment();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            runExperiment();
        } else {
            Toast.makeText(this, "Ohne Kamera-Berechtigung kein Test", Toast.LENGTH_LONG).show();
        }
    }

    private void runExperiment() {
        if (running) return;
        final SurfaceTexture texture = previewView.getSurfaceTexture();
        if (texture == null) {
            Toast.makeText(this, "Vorschau noch nicht bereit, bitte erneut tippen", Toast.LENGTH_LONG).show();
            return;
        }
        running = true;
        report = "";
        savedUri = null;
        out.setText("Aufloesungstest laeuft, App bitte offen lassen (ca. 2 Minuten) ...\n\n");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String r;
            try {
                Experiment experiment = new Experiment(this, texture,
                        line -> runOnUiThread(() -> out.append(line + "\n")));
                r = experiment.run();
            } catch (Throwable t) {
                r = "Fehler im Test: " + t;
            }
            final String result = r;
            runOnUiThread(() -> {
                report = result;
                out.append("\nBericht bereit: jetzt \"Teilen\" antippen.\n");
                running = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }).start();
    }

    private void copyReport() {
        if (report.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Kamera-Diagnose", report));
            Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Zu gross fuer die Zwischenablage, bitte als Datei teilen",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareReport() {
        if (report.isEmpty()) return;
        try {
            if (savedUri == null) {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, "kamera-diagnose-" + stamp + ".txt");
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Datei konnte nicht angelegt werden");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("Datei nicht beschreibbar");
                    os.write(report.getBytes(StandardCharsets.UTF_8));
                }
                savedUri = uri;
                Toast.makeText(this, "In Downloads gespeichert", Toast.LENGTH_SHORT).show();
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, savedUri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Diagnose teilen"));
        } catch (Throwable t) {
            Toast.makeText(this, "Teilen fehlgeschlagen: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ Bericht

    private String buildReport() throws Exception {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        StringBuilder sb = new StringBuilder();

        sb.append("KAMERA-DIAGNOSE v1.0\n");
        sb.append("Geraet:   ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android:  ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Build:    ").append(Build.DISPLAY).append('\n');
        sb.append("HyperOS:  ").append(sysProp("ro.mi.os.version.name"))
                .append(' ').append(sysProp("ro.mi.os.version.incremental")).append('\n');
        sb.append("Region:   ").append(sysProp("ro.miui.build.region"))
                .append(" / ").append(sysProp("ro.product.mod_device")).append('\n');
        sb.append("Chipsatz: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
        sb.append('\n');

        String[] listed = cm.getCameraIdList();
        Set<String> listedSet = new LinkedHashSet<>(Arrays.asList(listed));

        // Alle zu untersuchenden IDs: gelistete, physische hinter logischen Kameras, versteckte.
        Set<String> all = new LinkedHashSet<>(listedSet);
        Set<String> physicalOnly = new LinkedHashSet<>();
        for (String id : listed) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                for (String p : c.getPhysicalCameraIds()) {
                    if (!listedSet.contains(p)) physicalOnly.add(p);
                    all.add(p);
                }
            } catch (Throwable ignored) {
            }
        }
        Set<String> hidden = new LinkedHashSet<>();
        for (int i = 0; i < 256; i++) {
            String id = String.valueOf(i);
            if (all.contains(id)) continue;
            try {
                cm.getCameraCharacteristics(id);
                hidden.add(id);
                all.add(id);
            } catch (Throwable ignored) {
            }
        }

        sb.append("Offiziell gelistete IDs:       ").append(listedSet).append('\n');
        sb.append("Nur als physische Kamera:      ").append(physicalOnly).append('\n');
        sb.append("Versteckt, aber abfragbar:     ").append(hidden).append('\n');
        sb.append('\n');

        // Kurzuebersicht
        sb.append("=== UEBERSICHT ===\n");
        for (String id : all) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                sb.append(summaryLine(id, c, listedSet.contains(id))).append('\n');
            } catch (Throwable t) {
                sb.append("ID ").append(id).append(": nicht lesbar (").append(t).append(")\n");
            }
        }
        sb.append('\n');

        // Details
        for (String id : all) {
            sb.append("==================================================\n");
            sb.append("KAMERA ID ").append(id);
            if (listedSet.contains(id)) sb.append("  [gelistet]");
            if (physicalOnly.contains(id)) sb.append("  [nur physisch]");
            if (hidden.contains(id)) sb.append("  [versteckt]");
            sb.append('\n');
            sb.append("==================================================\n");
            try {
                describe(sb, cm.getCameraCharacteristics(id));
            } catch (Throwable t) {
                sb.append("Fehler: ").append(t).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String summaryLine(String id, CameraCharacteristics c, boolean isListed) {
        StringBuilder sb = new StringBuilder();
        sb.append("ID ").append(pad(id, 3)).append(' ');
        sb.append(pad(facing(get(c, CameraCharacteristics.LENS_FACING)), 6)).append(' ');

        float[] focal = get(c, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = get(c, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focal != null && focal.length > 0 && phys != null) {
            sb.append(pad(String.format(Locale.US, "~%.0fmm", equiv(focal[0], phys)), 7)).append(' ');
        } else {
            sb.append(pad("?", 7)).append(' ');
        }

        int[] caps = get(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean raw = has(caps, 3);
        boolean uhr = has(caps, 16);
        boolean logical = has(caps, 11);

        Size rawMax = largest(sizes(get(c, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP),
                ImageFormat.RAW_SENSOR));
        Size rawMaxRes = largest(sizes(
                get(c, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION),
                ImageFormat.RAW_SENSOR));

        sb.append("RAW: ").append(pad(rawMax == null ? "nein" : mp(rawMax), 20)).append(' ');
        sb.append("RAW max-res: ").append(pad(rawMaxRes == null ? "nein" : mp(rawMaxRes), 20));
        if (logical) sb.append(" [logisch]");
        if (uhr) sb.append(" [ULTRA_HIGH_RES]");
        if (!raw) sb.append(" [kein RAW-Flag]");
        if (!isListed) sb.append(" [nicht gelistet]");
        return sb.toString();
    }

    private void describe(StringBuilder sb, CameraCharacteristics c) {
        line(sb, "Richtung", facing(get(c, CameraCharacteristics.LENS_FACING)));
        line(sb, "Hardware-Level", hwLevel(get(c, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));

        int[] caps = get(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        line(sb, "Capabilities", names(caps, CAPS));

        try {
            Set<String> phys = c.getPhysicalCameraIds();
            if (!phys.isEmpty()) line(sb, "Physische Kameras", phys.toString());
        } catch (Throwable ignored) {
        }

        // Optik
        float[] focal = get(c, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF physSize = get(c, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        line(sb, "Brennweiten (mm)", focal == null ? "?" : Arrays.toString(focal));
        if (focal != null && focal.length > 0 && physSize != null) {
            line(sb, "KB-aequivalent", String.format(Locale.US, "~%.1f mm", equiv(focal[0], physSize)));
        }
        float[] apertures = get(c, CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        line(sb, "Blenden", apertures == null ? "?" : Arrays.toString(apertures));
        line(sb, "Min. Fokusdistanz (dpt)", str(get(c, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)));
        line(sb, "OIS-Modi", arr(get(c, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)));
        line(sb, "Zoom-Bereich", str(get(c, CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)));

        // Sensor
        line(sb, "Sensorgroesse (mm)", str(physSize));
        Size pixelArray = get(c, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        line(sb, "Pixel-Array", pixelArray == null ? "?" : mp(pixelArray));
        Rect active = get(c, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        line(sb, "Aktives Array", active == null ? "?" : active.width() + "x" + active.height());
        Size pixelArrayMax = get(c, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
        line(sb, "Pixel-Array MAX-RES", pixelArrayMax == null ? "nicht vorhanden" : mp(pixelArrayMax));
        line(sb, "Binning-Faktor", str(get(c, CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)));
        line(sb, "Farbfilter", cfa(get(c, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)));
        Integer white = get(c, CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        line(sb, "Weisspegel", white == null ? "?" : white + " (~" + bits(white) + " Bit)");
        line(sb, "ISO-Bereich", str(get(c, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)));
        line(sb, "Max. analoge ISO", str(get(c, CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)));
        Range<Long> exp = get(c, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exp != null) {
            line(sb, "Belichtungszeit", String.format(Locale.US, "1/%.0f s bis %.1f s",
                    1e9 / Math.max(1L, exp.getLower()), exp.getUpper() / 1e9));
        } else {
            line(sb, "Belichtungszeit", "?");
        }

        // Abschaltbare Verarbeitung (0 = OFF bei allen diesen Listen)
        sb.append("-- Verarbeitungsstufen (0 = abschaltbar) --\n");
        line(sb, "Rauschminderung", arr(get(c, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)));
        line(sb, "Schaerfung (Edge)", arr(get(c, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)));
        line(sb, "Hot-Pixel", arr(get(c, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)));
        line(sb, "Shading", arr(get(c, CameraCharacteristics.SHADING_AVAILABLE_MODES)));
        line(sb, "Verzeichnung", arr(get(c, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)));
        line(sb, "Farbsaeume (CA)", arr(get(c, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)));
        line(sb, "Tonemap-Modi", arr(get(c, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)));
        line(sb, "Videostabilisierung", arr(get(c, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)));

        // Streams
        StreamConfigurationMap map = get(c, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        sb.append("-- Ausgabeformate (Standardmodus) --\n");
        describeMap(sb, map, true);

        StreamConfigurationMap mapMax =
                get(c, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
        sb.append("-- Ausgabeformate (MAXIMUM_RESOLUTION-Modus) --\n");
        if (mapMax == null) {
            sb.append("  nicht vorhanden\n");
        } else {
            describeMap(sb, mapMax, false);
        }

        // Video
        sb.append("-- Video --\n");
        if (map != null) {
            try {
                Size[] rec = map.getOutputSizes(MediaRecorder.class);
                if (rec != null) {
                    List<Size> list = sorted(rec);
                    int n = Math.min(list.size(), 8);
                    for (int i = 0; i < n; i++) {
                        Size s = list.get(i);
                        long dur = map.getOutputMinFrameDuration(MediaRecorder.class, s);
                        String fps = dur > 0 ? String.format(Locale.US, "bis %.0f fps", 1e9 / dur) : "? fps";
                        sb.append("  ").append(pad(s.toString(), 11)).append(' ').append(fps).append('\n');
                    }
                }
            } catch (Throwable t) {
                sb.append("  Fehler: ").append(t).append('\n');
            }
            try {
                Size[] hs = map.getHighSpeedVideoSizes();
                if (hs != null && hs.length > 0) {
                    sb.append("  High-Speed:\n");
                    for (Size s : sorted(hs)) {
                        sb.append("    ").append(pad(s.toString(), 11)).append(' ')
                                .append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(s))).append('\n');
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        line(sb, "AE-FPS-Bereiche", arr(get(c, CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)));
        if (Build.VERSION.SDK_INT >= 33) {
            DynamicRangeProfiles profiles =
                    get(c, CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
            if (profiles == null) {
                line(sb, "10-Bit-Profile", "keine");
            } else {
                List<String> names = new ArrayList<>();
                for (Long p : profiles.getSupportedProfiles()) names.add(dynamicRange(p));
                line(sb, "10-Bit-Profile", names.toString());
            }
        }

        // Herstellerspezifische Schluessel
        sb.append("-- Hersteller-Schluessel (Eigenschaften) --\n");
        int vendorCount = 0;
        try {
            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                String name = key.getName();
                if (name.startsWith("android.")) continue;
                vendorCount++;
                String value;
                try {
                    value = valueToString(c.get(key));
                } catch (Throwable t) {
                    value = "(nicht lesbar)";
                }
                sb.append("  ").append(name).append(" = ").append(value).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  Fehler: ").append(t).append('\n');
        }
        if (vendorCount == 0) sb.append("  keine sichtbar\n");

        sb.append("-- Hersteller-Schluessel (Aufnahme-Parameter) --\n");
        int requestCount = 0;
        try {
            for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys()) {
                String name = key.getName();
                if (name.startsWith("android.")) continue;
                requestCount++;
                sb.append("  ").append(name).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  Fehler: ").append(t).append('\n');
        }
        if (requestCount == 0) sb.append("  keine sichtbar\n");
    }

    private void describeMap(StringBuilder sb, StreamConfigurationMap map, boolean withHighRes) {
        if (map == null) {
            sb.append("  keine Stream-Konfiguration\n");
            return;
        }
        int[] formats;
        try {
            formats = map.getOutputFormats();
        } catch (Throwable t) {
            sb.append("  Fehler: ").append(t).append('\n');
            return;
        }
        Arrays.sort(formats);
        for (int f : formats) {
            boolean isRaw = f == ImageFormat.RAW_SENSOR || f == ImageFormat.RAW10
                    || f == ImageFormat.RAW12 || f == ImageFormat.RAW_PRIVATE;
            List<Size> list = sorted(sizes(map, f));
            if (withHighRes) {
                try {
                    Size[] hi = map.getHighResolutionOutputSizes(f);
                    if (hi != null) {
                        for (Size s : hi) {
                            if (!list.contains(s)) list.add(s);
                        }
                        list = sorted(list.toArray(new Size[0]));
                    }
                } catch (Throwable ignored) {
                }
            }
            sb.append("  ").append(pad(format(f), 14));
            int limit = isRaw ? list.size() : Math.min(list.size(), 4);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                sb.append(mp(list.get(i)));
            }
            if (list.size() > limit) sb.append(", ... (").append(list.size()).append(" Groessen)");
            if (list.isEmpty()) sb.append("-");
            sb.append('\n');
        }
    }

    // ------------------------------------------------------------------- Helfer

    private static <T> T get(CameraCharacteristics c, CameraCharacteristics.Key<T> key) {
        try {
            return c.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Size[] sizes(StreamConfigurationMap map, int format) {
        if (map == null) return new Size[0];
        try {
            Size[] s = map.getOutputSizes(format);
            return s == null ? new Size[0] : s;
        } catch (Throwable t) {
            return new Size[0];
        }
    }

    private static List<Size> sorted(Size[] in) {
        List<Size> list = new ArrayList<>();
        if (in != null) list.addAll(Arrays.asList(in));
        list.sort((a, b) -> Long.compare(
                (long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        return list;
    }

    private static Size largest(Size[] in) {
        List<Size> list = sorted(in);
        return list.isEmpty() ? null : list.get(0);
    }

    private static String mp(Size s) {
        return String.format(Locale.US, "%dx%d (%.1f MP)",
                s.getWidth(), s.getHeight(), s.getWidth() * (double) s.getHeight() / 1e6);
    }

    private static double equiv(float focal, SizeF sensor) {
        double diag = Math.sqrt(sensor.getWidth() * (double) sensor.getWidth()
                + sensor.getHeight() * (double) sensor.getHeight());
        return diag <= 0 ? 0 : focal * 43.27 / diag;
    }

    private static int bits(int whiteLevel) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, whiteLevel));
    }

    private static boolean has(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) {
            if (v == wanted) return true;
        }
        return false;
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(pad(label + ":", 26)).append(value).append('\n');
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    private static String str(Object o) {
        return o == null ? "?" : String.valueOf(o);
    }

    private static String arr(Object o) {
        return o == null ? "?" : valueToString(o);
    }

    private static String valueToString(Object v) {
        if (v == null) return "null";
        String s;
        if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < n && i < 32; i++) {
                if (i > 0) b.append(", ");
                b.append(String.valueOf(Array.get(v, i)));
            }
            if (n > 32) b.append(", ... (").append(n).append(" Werte)");
            b.append(']');
            s = b.toString();
        } else {
            s = String.valueOf(v);
        }
        if (s.length() > 240) s = s.substring(0, 240) + "...";
        return s;
    }

    private static String sysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            String s = String.valueOf(v);
            return s.isEmpty() ? "-" : s;
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String facing(Integer f) {
        if (f == null) return "?";
        switch (f) {
            case 0:
                return "Front";
            case 1:
                return "Rueck";
            case 2:
                return "Extern";
            default:
                return String.valueOf(f);
        }
    }

    private static String hwLevel(Integer l) {
        if (l == null) return "?";
        switch (l) {
            case 0:
                return "LIMITED";
            case 1:
                return "FULL";
            case 2:
                return "LEGACY";
            case 3:
                return "LEVEL_3";
            case 4:
                return "EXTERNAL";
            default:
                return String.valueOf(l);
        }
    }

    private static String cfa(Integer v) {
        if (v == null) return "?";
        String[] n = {"RGGB", "GRBG", "GBRG", "BGGR", "RGB", "MONO", "NIR"};
        return v >= 0 && v < n.length ? n[v] : String.valueOf(v);
    }

    private static final String[] CAPS = {
            "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW",
            "PRIVATE_REPROCESSING", "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING",
            "DEPTH_OUTPUT", "CONSTRAINED_HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA",
            "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING",
            "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT",
            "STREAM_USE_CASE", "COLOR_SPACE_PROFILES"
    };

    private static String names(int[] values, String[] table) {
        if (values == null) return "?";
        List<String> list = new ArrayList<>();
        for (int v : values) {
            list.add(v >= 0 && v < table.length ? table[v] : String.valueOf(v));
        }
        return list.toString();
    }

    private static String format(int f) {
        switch (f) {
            case 0x20:
                return "RAW_SENSOR";
            case 0x24:
                return "RAW_PRIVATE";
            case 0x25:
                return "RAW10";
            case 0x26:
                return "RAW12";
            case 0x22:
                return "PRIVATE";
            case 0x23:
                return "YUV_420_888";
            case 0x36:
                return "YCBCR_P010";
            case 0x100:
                return "JPEG";
            case 0x1005:
                return "JPEG_R";
            case 0x48454946:
                return "HEIC";
            case 0x44363159:
                return "DEPTH16";
            case 0x20203859:
                return "Y8";
            default:
                return "0x" + Integer.toHexString(f);
        }
    }

    private static String dynamicRange(long p) {
        if (p == 1L) return "STANDARD";
        if (p == 2L) return "HLG10";
        if (p == 4L) return "HDR10";
        if (p == 8L) return "HDR10_PLUS";
        if (p == 16L) return "DOLBY_VISION_10B_HDR_REF";
        if (p == 32L) return "DOLBY_VISION_10B_HDR_REF_PO";
        if (p == 64L) return "DOLBY_VISION_10B_HDR_OEM";
        if (p == 128L) return "DOLBY_VISION_10B_HDR_OEM_PO";
        if (p == 256L) return "DOLBY_VISION_8B_HDR_REF";
        if (p == 512L) return "DOLBY_VISION_8B_HDR_REF_PO";
        if (p == 1024L) return "DOLBY_VISION_8B_HDR_OEM";
        if (p == 2048L) return "DOLBY_VISION_8B_HDR_OEM_PO";
        return String.valueOf(p);
    }
}
