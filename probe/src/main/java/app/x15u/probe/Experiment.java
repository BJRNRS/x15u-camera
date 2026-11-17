package app.x15u.probe;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prueft mit echten Aufnahmen, ob das Geraet mehr als die offiziell gemeldete
 * RAW-Aufloesung herausgibt: groessere Puffer, Hersteller-Schalter, Pixelmodus.
 * Es wird nichts gespeichert, nur die Groesse der gelieferten Bilder protokolliert.
 */
final class Experiment {

    interface Progress {
        void line(String s);
    }

    private static final String KEY_REMOSAIC = "com.xiaomi.control.qcfa.isSuperRemosaic";
    private static final String KEY_IDEAL_RAW = "org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW";
    private static final String KEY_CLIENT = "com.xiaomi.sessionparams.clientName";
    private static final String STOCK_CAMERA = "com.android.camera";

    private final CameraManager cm;
    private final Progress progress;
    private final StringBuilder log = new StringBuilder();
    private final Map<String, String> vendorTypes = new LinkedHashMap<>();
    private HandlerThread thread;
    private Handler handler;
    private Executor executor;

    Experiment(Context context, Progress progress) {
        this.cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.progress = progress;
    }

    String run() {
        thread = new HandlerThread("experiment");
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = r -> handler.post(r);
        try {
            say("AUFLOESUNGSTEST v1.2");
            say("Geraet:  " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
            say("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.DISPLAY);
            say("");

            List<String> ids = new ArrayList<>();
            try {
                CameraCharacteristics logical = cm.getCameraCharacteristics("0");
                ids.addAll(logical.getPhysicalCameraIds());
            } catch (Throwable t) {
                say("Physische Kameras nicht lesbar: " + msg(t));
            }
            if (ids.isEmpty()) ids.add("0");

            say("=== Kontrolle: logische Kamera 0 ohne Zusatz ===");
            try {
                Size b0 = largestRaw(cm.getCameraCharacteristics("0"));
                if (b0 != null) {
                    Variant v0 = new Variant("RAW Basis", b0.getWidth(), b0.getHeight(),
                            ImageFormat.RAW_SENSOR, 0);
                    say("  " + pad(v0.label, 40) + runVariant("0", null, v0));
                    Variant j0 = new Variant("JPEG Basis", b0.getWidth(), b0.getHeight(),
                            ImageFormat.JPEG, 0);
                    say("  " + pad(j0.label, 40) + runVariant("0", null, j0));
                }
            } catch (Throwable t) {
                say("  FEHLER: " + msg(t));
            }
            say("");

            for (String id : ids) {
                try {
                    testCamera(id);
                } catch (Throwable t) {
                    say("Kamera " + id + ": abgebrochen (" + msg(t) + ")");
                }
                say("");
            }

            say("=== Erkannte Typen der Hersteller-Schalter ===");
            if (vendorTypes.isEmpty()) say("  keiner liess sich setzen");
            for (Map.Entry<String, String> e : vendorTypes.entrySet()) {
                say("  " + e.getKey() + " -> " + e.getValue());
            }
            say("");

            scanConfigFiles();
            say("");
            say("FERTIG");
        } finally {
            thread.quitSafely();
        }
        return log.toString();
    }

    // ------------------------------------------------------------ Kamera-Test

    private static final class Variant {
        final String label;
        final int width;
        final int height;
        final int format;
        final int vendorLevel;

        Variant(String label, int width, int height, int format, int vendorLevel) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.format = format;
            this.vendorLevel = vendorLevel;
        }
    }

    private void testCamera(String physId) throws Exception {
        say("=== Kamera " + physId + " ===");
        CameraCharacteristics c = cm.getCameraCharacteristics(physId);

        say("Versteckte Sensor-Eigenschaften:");
        String[] names = {
                "org.codeaurora.qcamera3.quadra_cfa.is_qcfa_sensor",
                "org.codeaurora.qcamera3.quadra_cfa.qcfa_dimension",
                "xiaomi.scaler.availableSuperResolutionStreamConfigurations",
        };
        for (String n : names) {
            say("  " + n + " = " + readCharacteristic(c, n));
        }

        Size base = largestRaw(c);
        if (base == null) {
            say("Kein RAW-Format gemeldet, Kamera uebersprungen");
            return;
        }
        int bw = base.getWidth();
        int bh = base.getHeight();
        int raw = ImageFormat.RAW_SENSOR;
        int yuv = ImageFormat.YUV_420_888;
        int jpeg = ImageFormat.JPEG;
        String s2 = 2 * bw + "x" + 2 * bh;

        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("RAW " + bw + "x" + bh + " (Basis)", bw, bh, raw, 0));
        variants.add(new Variant("RAW Basis + Schalter", bw, bh, raw, 1));
        variants.add(new Variant("RAW " + s2, 2 * bw, 2 * bh, raw, 0));
        variants.add(new Variant("RAW " + s2 + " + Schalter", 2 * bw, 2 * bh, raw, 1));
        variants.add(new Variant("RAW " + s2 + " + Schalter + Client", 2 * bw, 2 * bh, raw, 2));
        variants.add(new Variant("YUV " + s2 + " + Schalter + Client", 2 * bw, 2 * bh, yuv, 2));
        variants.add(new Variant("JPEG " + s2, 2 * bw, 2 * bh, jpeg, 0));
        variants.add(new Variant("JPEG " + s2 + " + Schalter + Client", 2 * bw, 2 * bh, jpeg, 2));
        if (bw == 4080) {
            String s4 = 4 * bw + "x" + 4 * bh;
            variants.add(new Variant("JPEG " + s4 + " + Schalter + Client", 4 * bw, 4 * bh, jpeg, 2));
            variants.add(new Variant("RAW " + s4 + " + Schalter + Client", 4 * bw, 4 * bh, raw, 2));
        }

        say("Ueber logische Kamera 0 (offizieller Weg):");
        for (Variant v : variants) {
            String result;
            try {
                result = runVariant("0", physId, v);
            } catch (Throwable t) {
                result = "FEHLER: " + msg(t);
            }
            say("  " + pad(v.label, 40) + result);
        }

        say("Direkt geoeffnet:");
        Variant direct = variants.get(0);
        String result;
        try {
            result = runVariant(physId, null, direct);
        } catch (Throwable t) {
            result = "FEHLER: " + msg(t);
        }
        say("  " + pad(direct.label, 40) + result);
    }

    /** Oeffnet die Kamera frisch, startet eine kleine Vorschau und loest ein Einzelbild aus. */
    private String runVariant(String openId, String physical, Variant v) throws Exception {
        CameraDevice device = open(openId);
        ImageReader still = null;
        ImageReader preview = null;
        final CameraCaptureSession[] session = new CameraCaptureSession[1];
        try {
            still = ImageReader.newInstance(v.width, v.height, v.format, 1);
            preview = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2);

            final AtomicInteger previewFrames = new AtomicInteger();
            preview.setOnImageAvailableListener(r -> {
                try (Image img = r.acquireLatestImage()) {
                    if (img != null) previewFrames.incrementAndGet();
                } catch (Throwable ignored) {
                }
            }, handler);

            final CountDownLatch imageLatch = new CountDownLatch(1);
            final String[] imageInfo = new String[1];
            still.setOnImageAvailableListener(r -> {
                try (Image img = r.acquireNextImage()) {
                    if (img != null) {
                        int bytes = img.getPlanes()[0].getBuffer().remaining();
                        imageInfo[0] = img.getWidth() + "x" + img.getHeight()
                                + String.format(java.util.Locale.US, " (%.1f MB)", bytes / 1048576.0);
                    }
                } catch (Throwable t) {
                    imageInfo[0] = "nicht lesbar: " + msg(t);
                }
                imageLatch.countDown();
            }, handler);

            CaptureRequest.Builder previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(preview.getSurface());
            applyVendor(previewRequest, v.vendorLevel);

            OutputConfiguration previewOut = new OutputConfiguration(preview.getSurface());
            OutputConfiguration stillOut = new OutputConfiguration(still.getSurface());
            if (physical != null) {
                previewOut.setPhysicalCameraId(physical);
                stillOut.setPhysicalCameraId(physical);
            }
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(previewOut);
            outputs.add(stillOut);

            final CountDownLatch configLatch = new CountDownLatch(1);
            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session[0] = s;
                            configLatch.countDown();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            configLatch.countDown();
                        }
                    });
            config.setSessionParameters(previewRequest.build());
            device.createCaptureSession(config);

            if (!configLatch.await(10, TimeUnit.SECONDS)) return "Timeout beim Einrichten";
            if (session[0] == null) return "abgelehnt (Groesse/Format nicht erlaubt)";

            session[0].setRepeatingRequest(previewRequest.build(), null, handler);
            Thread.sleep(1200);

            CaptureRequest.Builder stillRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            stillRequest.addTarget(still.getSurface());
            applyVendor(stillRequest, v.vendorLevel);

            final String[] captureError = new String[1];
            session[0].capture(stillRequest.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                            CaptureFailure failure) {
                    captureError[0] = "Aufnahme fehlgeschlagen (Grund " + failure.getReason() + ")";
                    imageLatch.countDown();
                }
            }, handler);

            String frames = ", Vorschau-Frames: " + previewFrames.get();
            if (!imageLatch.await(15, TimeUnit.SECONDS)) {
                return "eingerichtet, aber kein Bild (Timeout)" + frames;
            }
            if (imageInfo[0] == null) {
                return (captureError[0] != null ? captureError[0] : "eingerichtet, aber kein Bild") + frames;
            }
            return "BILD " + imageInfo[0];
        } finally {
            closeQuietly(device);
            try {
                if (still != null) still.close();
            } catch (Throwable ignored) {
            }
            try {
                if (preview != null) preview.close();
            } catch (Throwable ignored) {
            }
            Thread.sleep(350);
        }
    }

    private CameraDevice open(String id) throws Exception {
        final CameraDevice[] device = new CameraDevice[1];
        final String[] error = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice d) {
                device[0] = d;
                latch.countDown();
            }

            @Override
            public void onDisconnected(CameraDevice d) {
                error[0] = "Verbindung getrennt";
                latch.countDown();
            }

            @Override
            public void onError(CameraDevice d, int code) {
                error[0] = "Fehlercode " + code;
                latch.countDown();
            }
        }, handler);
        if (!latch.await(10, TimeUnit.SECONDS)) throw new Exception("Timeout beim Oeffnen");
        if (device[0] == null) throw new Exception(error[0]);
        return device[0];
    }

    private static void closeQuietly(CameraDevice device) {
        try {
            if (device != null) device.close();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------ Hersteller-Schalter

    private void applyVendor(CaptureRequest.Builder builder, int level) {
        if (level >= 1) {
            note(KEY_REMOSAIC, setNumber(builder, KEY_REMOSAIC, 1));
            note(KEY_IDEAL_RAW, setNumber(builder, KEY_IDEAL_RAW, 1));
        }
        if (level >= 2) {
            note(KEY_CLIENT, setText(builder, KEY_CLIENT, STOCK_CAMERA));
        }
    }

    private void note(String key, String type) {
        if (!vendorTypes.containsKey(key) || vendorTypes.get(key).startsWith("nicht")) {
            vendorTypes.put(key, type == null ? "nicht setzbar" : type);
        }
    }

    private static <T> void put(CaptureRequest.Builder builder, String name, Class<T> type, T value) {
        builder.set(new CaptureRequest.Key<T>(name, type), value);
    }

    private static String setNumber(CaptureRequest.Builder builder, String name, int value) {
        try {
            put(builder, name, Byte.class, (byte) value);
            return "byte";
        } catch (Throwable ignored) {
        }
        try {
            put(builder, name, Integer.class, value);
            return "int";
        } catch (Throwable ignored) {
        }
        try {
            put(builder, name, Long.class, (long) value);
            return "long";
        } catch (Throwable ignored) {
        }
        try {
            put(builder, name, Boolean.class, value != 0);
            return "bool";
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String setText(CaptureRequest.Builder builder, String name, String value) {
        try {
            put(builder, name, String.class, value);
            return "string";
        } catch (Throwable ignored) {
        }
        try {
            byte[] raw = value.getBytes(StandardCharsets.UTF_8);
            byte[] terminated = new byte[raw.length + 1];
            System.arraycopy(raw, 0, terminated, 0, raw.length);
            put(builder, name, byte[].class, terminated);
            return "byte[]";
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static <T> Object getCharacteristic(CameraCharacteristics c, String name, Class<T> type) {
        return c.get(new CameraCharacteristics.Key<T>(name, type));
    }

    private static String readCharacteristic(CameraCharacteristics c, String name) {
        Class<?>[] types = {int[].class, byte[].class, long[].class, float[].class};
        for (Class<?> type : types) {
            try {
                Object value = getCharacteristic(c, name, type);
                return value == null ? "vorhanden, aber leer" : describe(value);
            } catch (Throwable ignored) {
            }
        }
        return "nicht vorhanden";
    }

    private static String describe(Object value) {
        if (!value.getClass().isArray()) return String.valueOf(value);
        int n = Array.getLength(value);
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < n && i < 64; i++) {
            if (i > 0) b.append(", ");
            b.append(Array.get(value, i));
        }
        if (n > 64) b.append(", ... (").append(n).append(" Werte)");
        return b.append(']').toString();
    }

    // -------------------------------------------------------- Konfig-Dateien

    private void scanConfigFiles() {
        say("=== Kamera-Konfigurationsdateien ===");
        String[] dirs = {"/vendor/etc/camera", "/odm/etc/camera"};
        List<File> textFiles = new ArrayList<>();
        for (String dir : dirs) {
            File[] files;
            try {
                files = new File(dir).listFiles();
            } catch (Throwable t) {
                files = null;
            }
            if (files == null) {
                say(dir + ": nicht lesbar");
                continue;
            }
            for (File f : files) {
                String lower = f.getName().toLowerCase(java.util.Locale.US);
                boolean text = lower.endsWith(".xml") || lower.endsWith(".json")
                        || lower.endsWith(".txt") || lower.endsWith(".conf") || lower.endsWith(".cfg");
                if (f.isFile() && text) {
                    textFiles.add(f);
                    say("  " + f.getPath() + " (" + f.length() + " B)");
                }
            }
        }
        say("(Der Inhalt der wichtigsten Dateien steht nur in der geteilten Datei, nicht auf dem Bildschirm.)");

        String[] wanted = {"cameracustomize.json", "camxoverridesettings.txt",
                "cameraopt_vendor.json", "cameraopt_perf.json"};
        for (File f : textFiles) {
            boolean dump = false;
            for (String w : wanted) {
                if (f.getName().equalsIgnoreCase(w)) dump = true;
            }
            if (!dump) continue;
            quiet("");
            quiet("##### DATEI " + f.getPath() + " #####");
            int chars = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 4000) line = line.substring(0, 4000) + "...";
                    quiet(line);
                    chars += line.length();
                    if (chars > 400_000) {
                        quiet("... (gekuerzt)");
                        break;
                    }
                }
            } catch (Throwable t) {
                quiet("nicht lesbar: " + msg(t));
            }
        }
    }

    private void quiet(String s) {
        log.append(s).append('\n');
    }

    // ------------------------------------------------------------------ Helfer

    private static Size largestRaw(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private void say(String s) {
        log.append(s).append('\n');
        try {
            progress.line(s);
        } catch (Throwable ignored) {
        }
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }
}
