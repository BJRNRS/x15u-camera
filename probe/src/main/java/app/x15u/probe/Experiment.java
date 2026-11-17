package app.x15u.probe;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prueft mit echten Aufnahmen, was das Geraet einer Dritt-App liefert.
 * Aufbau wie in einer normalen Kamera-App: sichtbare Vorschau plus Einzelbild.
 * Jeder Versuch protokolliert, wie weit er gekommen ist. Gespeichert wird nichts.
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
    private final SurfaceTexture previewTexture;
    private final Progress progress;
    private final StringBuilder log = new StringBuilder();
    private final Map<String, String> vendorTypes = new LinkedHashMap<>();
    private HandlerThread thread;
    private Handler handler;
    private Executor executor;
    private volatile int deviceError;

    Experiment(Context context, SurfaceTexture previewTexture, Progress progress) {
        this.cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.previewTexture = previewTexture;
        this.progress = progress;
    }

    String run() {
        thread = new HandlerThread("experiment");
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = r -> handler.post(r);
        try {
            say("AUFLOESUNGSTEST v1.3");
            say("Geraet:  " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
            say("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.DISPLAY);
            say("");

            Size base = largestRaw("0");
            if (base == null) {
                say("Kamera 0 meldet kein RAW, Abbruch");
                return log.toString();
            }
            int bw = base.getWidth();
            int bh = base.getHeight();
            int raw = ImageFormat.RAW_SENSOR;
            int jpeg = ImageFormat.JPEG;
            int yuv = ImageFormat.YUV_420_888;

            say("=== 1. Grundfunktion, Kamera 0 (Hauptkamera) ===");
            boolean previewOk = test("Nur Vorschau", "0", null, 0, 0, 0, 0);
            boolean jpegOk = test("Vorschau + JPEG " + bw + "x" + bh, "0", null, jpeg, bw, bh, 0);
            boolean rawOk = test("Vorschau + RAW " + bw + "x" + bh, "0", null, raw, bw, bh, 0);
            say("");

            if (!previewOk) {
                say("Schon die Vorschau scheitert. Weitere Versuche waeren nicht aussagekraeftig.");
                return log.toString();
            }

            say("=== 2. Volle Aufloesung, Hauptkamera ===");
            int w2 = 2 * bw;
            int h2 = 2 * bh;
            String s2 = w2 + "x" + h2;
            test("JPEG " + s2, "0", null, jpeg, w2, h2, 0);
            test("JPEG " + s2 + " + Schalter", "0", null, jpeg, w2, h2, 1);
            test("JPEG " + s2 + " + Schalter + Client", "0", null, jpeg, w2, h2, 2);
            test("YUV " + s2 + " + Schalter + Client", "0", null, yuv, w2, h2, 2);
            test("RAW " + s2, "0", null, raw, w2, h2, 0);
            test("RAW " + s2 + " + Schalter", "0", null, raw, w2, h2, 1);
            test("RAW " + s2 + " + Schalter + Client", "0", null, raw, w2, h2, 2);
            test("RAW " + bw + "x" + bh + " + Schalter", "0", null, raw, bw, bh, 1);
            say("");

            say("=== 3. Einzelne Objektive ueber Kamera 0 ===");
            List<String> physical = new ArrayList<>();
            try {
                physical.addAll(cm.getCameraCharacteristics("0").getPhysicalCameraIds());
            } catch (Throwable t) {
                say("  Physische Kameras nicht lesbar: " + msg(t));
            }
            for (String id : physical) {
                Size pb = largestRaw(id);
                if (pb == null) {
                    say("  Kamera " + id + ": keine RAW-Groesse lesbar");
                    continue;
                }
                test("Objektiv " + id + ": " + (rawOk ? "RAW " : "JPEG ") + pb.getWidth() + "x" + pb.getHeight(),
                        "0", id, rawOk ? raw : jpeg, pb.getWidth(), pb.getHeight(), 0);
            }
            say("");

            say("=== 4. Periskop-Tele, volle Aufloesung ===");
            for (String id : physical) {
                Size pb = largestRaw(id);
                if (pb == null || pb.getWidth() != 4080) continue;
                int pw = pb.getWidth();
                int ph = pb.getHeight();
                test("JPEG " + 2 * pw + "x" + 2 * ph + " + Schalter + Client", "0", id, jpeg, 2 * pw, 2 * ph, 2);
                test("JPEG " + 4 * pw + "x" + 4 * ph + " + Schalter + Client", "0", id, jpeg, 4 * pw, 4 * ph, 2);
                test("RAW " + 2 * pw + "x" + 2 * ph + " + Schalter + Client", "0", id, raw, 2 * pw, 2 * ph, 2);
            }
            say("");

            say("=== Erkannte Typen der Hersteller-Schalter ===");
            if (vendorTypes.isEmpty()) say("  keiner liess sich setzen");
            for (Map.Entry<String, String> e : vendorTypes.entrySet()) {
                say("  " + e.getKey() + " -> " + e.getValue());
            }
            say("");
            say("Grundfunktion: Vorschau=" + previewOk + ", JPEG=" + jpegOk + ", RAW=" + rawOk);
            say("FERTIG");
        } catch (Throwable t) {
            say("Abbruch: " + msg(t));
        } finally {
            thread.quitSafely();
        }
        return log.toString();
    }

    /** Fuehrt einen Versuch aus, protokolliert ihn und meldet, ob ein Bild (bzw. die Vorschau) kam. */
    private boolean test(String label, String openId, String physical,
                         int format, int width, int height, int vendorLevel) {
        String result;
        boolean ok = false;
        try {
            result = runVariant(openId, physical, format, width, height, vendorLevel);
            ok = result.contains("BILD ") || (format == 0 && result.contains("Vorschau ok"));
        } catch (Throwable t) {
            result = "FEHLER: " + msg(t);
        }
        say("  " + label);
        say("      " + result);
        try {
            Thread.sleep(ok ? 500 : 3000);
        } catch (InterruptedException ignored) {
        }
        return ok;
    }

    private String runVariant(String openId, String physical,
                              int format, int width, int height, int vendorLevel) {
        StringBuilder stage = new StringBuilder();
        deviceError = 0;
        CameraDevice device = null;
        ImageReader still = null;
        Surface previewSurface = null;
        try {
            device = open(openId);
            stage.append("geoeffnet");

            Size previewSize = pickPreviewSize(openId);
            previewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(previewTexture);

            List<OutputConfiguration> outputs = new ArrayList<>();
            OutputConfiguration previewOut = new OutputConfiguration(previewSurface);
            if (physical != null) previewOut.setPhysicalCameraId(physical);
            outputs.add(previewOut);

            final CountDownLatch imageLatch = new CountDownLatch(1);
            final String[] imageInfo = new String[1];
            if (format != 0) {
                still = ImageReader.newInstance(width, height, format, 2);
                still.setOnImageAvailableListener(r -> {
                    try (Image img = r.acquireNextImage()) {
                        if (img != null) {
                            int bytes = img.getPlanes()[0].getBuffer().remaining();
                            imageInfo[0] = img.getWidth() + "x" + img.getHeight()
                                    + String.format(Locale.US, " (%.1f MB)", bytes / 1048576.0);
                        }
                    } catch (Throwable t) {
                        imageInfo[0] = "nicht lesbar: " + msg(t);
                    }
                    imageLatch.countDown();
                }, handler);
                OutputConfiguration stillOut = new OutputConfiguration(still.getSurface());
                if (physical != null) stillOut.setPhysicalCameraId(physical);
                outputs.add(stillOut);
            }

            CaptureRequest.Builder previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(previewSurface);
            applyVendor(previewRequest, vendorLevel);

            final CountDownLatch configLatch = new CountDownLatch(1);
            final CameraCaptureSession[] session = new CameraCaptureSession[1];
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
            if (vendorLevel > 0) config.setSessionParameters(previewRequest.build());
            device.createCaptureSession(config);

            if (!configLatch.await(10, TimeUnit.SECONDS)) return stage + " > Timeout beim Einrichten" + err();
            if (session[0] == null) return stage + " > Session ABGELEHNT" + err();
            stage.append(" > Session ok");

            final AtomicInteger frames = new AtomicInteger();
            session[0].setRepeatingRequest(previewRequest.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    frames.incrementAndGet();
                }
            }, handler);
            Thread.sleep(1500);
            int n = frames.get();
            if (n == 0) return stage + " > keine Vorschau-Frames" + err();
            stage.append(" > Vorschau ok (").append(n).append(" Frames)");
            if (format == 0) return stage.toString();

            CaptureRequest.Builder stillRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            stillRequest.addTarget(still.getSurface());
            applyVendor(stillRequest, vendorLevel);

            final String[] captureError = new String[1];
            session[0].capture(stillRequest.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                            CaptureFailure failure) {
                    captureError[0] = "Aufnahme fehlgeschlagen (Grund " + failure.getReason() + ")";
                    imageLatch.countDown();
                }
            }, handler);

            if (!imageLatch.await(15, TimeUnit.SECONDS)) return stage + " > kein Bild (Timeout)" + err();
            if (imageInfo[0] == null) {
                return stage + " > " + (captureError[0] != null ? captureError[0] : "kein Bild") + err();
            }
            return stage + " > BILD " + imageInfo[0];
        } catch (Throwable t) {
            return (stage.length() == 0 ? "" : stage + " > ") + "FEHLER: " + msg(t) + err();
        } finally {
            try {
                if (device != null) device.close();
            } catch (Throwable ignored) {
            }
            try {
                if (still != null) still.close();
            } catch (Throwable ignored) {
            }
            try {
                if (previewSurface != null) previewSurface.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private String err() {
        int e = deviceError;
        return e == 0 ? "" : " [Geraetefehler " + e + "]";
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
                deviceError = -1;
                latch.countDown();
            }

            @Override
            public void onError(CameraDevice d, int code) {
                error[0] = "Fehlercode " + code;
                deviceError = code;
                latch.countDown();
            }
        }, handler);
        if (!latch.await(10, TimeUnit.SECONDS)) throw new Exception("Timeout beim Oeffnen");
        if (device[0] == null) throw new Exception(error[0]);
        return device[0];
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
            byte[] rawBytes = value.getBytes(StandardCharsets.UTF_8);
            byte[] terminated = new byte[rawBytes.length + 1];
            System.arraycopy(rawBytes, 0, terminated, 0, rawBytes.length);
            put(builder, name, byte[].class, terminated);
            return "byte[]";
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ Helfer

    private Size largestRaw(String id) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) return null;
                Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
                if (sizes == null || sizes.length == 0) return null;
                Size best = sizes[0];
                for (Size s : sizes) {
                    if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
                }
                return best;
            } catch (Throwable t) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return null;
    }

    /** Groesste 4:3-Vorschau bis 1440x1080, sonst 1280x720. */
    private Size pickPreviewSize(String id) {
        Size fallback = new Size(1280, 720);
        try {
            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return fallback;
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null) return fallback;
            Size best = null;
            for (Size s : sizes) {
                if (s.getWidth() > 1440 || s.getHeight() > 1080) continue;
                if (s.getWidth() * 3 != s.getHeight() * 4) continue;
                if (best == null || s.getWidth() > best.getWidth()) best = s;
            }
            return best != null ? best : fallback;
        } catch (Throwable t) {
            return fallback;
        }
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
        return s.length() > 180 ? s.substring(0, 180) + "..." : s;
    }
}
