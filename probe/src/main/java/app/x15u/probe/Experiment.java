package app.x15u.probe;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
    private volatile boolean deviceBroken;

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
            say("AUFLOESUNGSTEST v1.1");
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
        final boolean pixelMode;

        Variant(String label, int width, int height, int format, int vendorLevel, boolean pixelMode) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.format = format;
            this.vendorLevel = vendorLevel;
            this.pixelMode = pixelMode;
        }
    }

    private void testCamera(String physId) throws Exception {
        say("=== Kamera " + physId + " ===");
        CameraCharacteristics c = cm.getCameraCharacteristics(physId);

        say("Versteckte Sensor-Eigenschaften:");
        String[] names = {
                "org.codeaurora.qcamera3.quadra_cfa.is_qcfa_sensor",
                "org.codeaurora.qcamera3.quadra_cfa.qcfa_dimension",
                "org.codeaurora.qcamera3.quadra_cfa.activeArraySize",
                "org.codeaurora.qcamera3.quadra_cfa.availableStreamConfigurations",
                "xiaomi.scaler.availableStreamConfigurations",
                "xiaomi.scaler.availableSuperResolutionStreamConfigurations",
                "xiaomi.scaler.availableLimitStreamConfigurations",
                "xiaomi.sensor.info.activeArraySize",
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

        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("RAW " + bw + "x" + bh + " (Basis)", bw, bh, raw, 0, false));
        variants.add(new Variant("RAW Basis + Schalter", bw, bh, raw, 1, false));
        variants.add(new Variant("RAW Basis + Schalter + Client", bw, bh, raw, 2, false));
        variants.add(new Variant("RAW " + 2 * bw + "x" + 2 * bh, 2 * bw, 2 * bh, raw, 0, false));
        variants.add(new Variant("RAW 2x + Schalter", 2 * bw, 2 * bh, raw, 1, false));
        variants.add(new Variant("RAW 2x + Schalter + Client", 2 * bw, 2 * bh, raw, 2, false));
        variants.add(new Variant("RAW 2x + Pixelmodus", 2 * bw, 2 * bh, raw, 2, true));
        if (bw == 4080) {
            variants.add(new Variant("RAW " + 4 * bw + "x" + 4 * bh + " + Schalter + Client",
                    4 * bw, 4 * bh, raw, 2, false));
        }
        variants.add(new Variant("JPEG 2x + Schalter + Client", 2 * bw, 2 * bh, ImageFormat.JPEG, 2, false));

        String openId = physId;
        String physical = null;
        CameraDevice device;
        try {
            device = open(physId);
            say("Direkt oeffnen: OK");
        } catch (Throwable t) {
            say("Direkt oeffnen: FEHLER (" + msg(t) + "), nutze logische Kamera 0");
            openId = "0";
            physical = physId;
            device = open("0");
        }

        try {
            for (Variant v : variants) {
                if (deviceBroken) {
                    closeQuietly(device);
                    device = null;
                    deviceBroken = false;
                    device = open(openId);
                }
                String result;
                try {
                    result = runVariant(device, physical, v);
                } catch (Throwable t) {
                    result = "FEHLER: " + msg(t);
                }
                say("  " + pad(v.label, 36) + result);
            }
        } finally {
            closeQuietly(device);
            deviceBroken = false;
            Thread.sleep(400);
        }
    }

    private String runVariant(CameraDevice device, String physical, Variant v) throws Exception {
        ImageReader reader = ImageReader.newInstance(v.width, v.height, v.format, 1);
        final CameraCaptureSession[] session = new CameraCaptureSession[1];
        try {
            final CountDownLatch imageLatch = new CountDownLatch(1);
            final String[] imageInfo = new String[1];
            reader.setOnImageAvailableListener(r -> {
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

            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(reader.getSurface());
            applyVendor(builder, v.vendorLevel);
            if (v.pixelMode) {
                builder.set(CaptureRequest.SENSOR_PIXEL_MODE,
                        CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            }

            OutputConfiguration output = new OutputConfiguration(reader.getSurface());
            if (physical != null) output.setPhysicalCameraId(physical);
            if (v.pixelMode) {
                output.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            }

            final CountDownLatch configLatch = new CountDownLatch(1);
            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(output),
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
            config.setSessionParameters(builder.build());
            device.createCaptureSession(config);

            if (!configLatch.await(10, TimeUnit.SECONDS)) return "Timeout beim Einrichten";
            if (session[0] == null) return "abgelehnt (Groesse/Format nicht erlaubt)";

            final String[] captureError = new String[1];
            session[0].capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                            CaptureFailure failure) {
                    captureError[0] = "Aufnahme fehlgeschlagen (Grund " + failure.getReason() + ")";
                    imageLatch.countDown();
                }
            }, handler);

            if (!imageLatch.await(15, TimeUnit.SECONDS)) return "eingerichtet, aber kein Bild (Timeout)";
            if (imageInfo[0] == null) {
                return captureError[0] != null ? captureError[0] : "eingerichtet, aber kein Bild";
            }
            return "BILD " + imageInfo[0];
        } finally {
            try {
                if (session[0] != null) session[0].close();
            } catch (Throwable ignored) {
            }
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
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
                deviceBroken = true;
                latch.countDown();
            }

            @Override
            public void onError(CameraDevice d, int code) {
                error[0] = "Fehlercode " + code;
                deviceBroken = true;
                latch.countDown();
            }
        }, handler);
        if (!latch.await(10, TimeUnit.SECONDS)) throw new Exception("Timeout beim Oeffnen");
        if (device[0] == null) {
            deviceBroken = false;
            throw new Exception(error[0]);
        }
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
        String[] dirs = {
                "/vendor/etc/camera", "/odm/etc/camera", "/product/etc/camera",
                "/system/etc/camera", "/system_ext/etc/camera", "/mi_ext/product/etc/camera",
        };
        Pattern interesting = Pattern.compile(
                "(?i)white.?list|allow.?list|package.?name|clientname|thirdparty|3rd");
        int hits = 0;
        for (String dir : dirs) {
            File d = new File(dir);
            File[] files;
            try {
                files = d.listFiles();
            } catch (Throwable t) {
                files = null;
            }
            if (files == null) {
                say(dir + ": nicht lesbar");
                continue;
            }
            say(dir + ": " + files.length + " Eintraege");
            int shown = 0;
            for (File f : files) {
                if (shown++ < 60) say("  " + f.getName() + (f.isDirectory() ? "/" : " (" + f.length() + " B)"));
                String lower = f.getName().toLowerCase(java.util.Locale.US);
                boolean text = lower.endsWith(".xml") || lower.endsWith(".json")
                        || lower.endsWith(".txt") || lower.endsWith(".conf") || lower.endsWith(".cfg");
                if (!f.isFile() || !text || f.length() > 3_000_000L || hits >= 80) continue;
                try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = reader.readLine()) != null && hits < 80) {
                        if (interesting.matcher(line).find()) {
                            String trimmed = line.trim();
                            if (trimmed.length() > 200) trimmed = trimmed.substring(0, 200) + "...";
                            say("    > " + f.getName() + ": " + trimmed);
                            hits++;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (hits == 0) say("Keine Hinweise auf App-Freigabelisten gefunden (oder Dateien nicht lesbar)");
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
