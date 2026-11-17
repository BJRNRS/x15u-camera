package app.x15u.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Steuert die Kamera. Alle vier Rueckobjektive laufen ueber die logische Kamera "0" und werden
 * ueber ihre physische Kennung gezielt angesprochen; wahlweise lassen sie sich direkt oeffnen.
 */
final class CameraController {

    static final int MODE_RAW = 0;
    static final int MODE_LOOK = 1;
    static final int MODE_BOTH = 2;

    private static final String LOGICAL_ID = "0";

    interface Listener {
        void onInfo(String text);

        void onStatus(String text);

        void onBusy(boolean busy);
    }

    static final class Lens {
        String id;
        float equivalent;
        float aperture;
        String label;
        Size raw;
        Size jpeg;
        Size preview;
        Range<Integer> iso;
        Range<Long> exposure;
        float minFocusDiopters;
        int evMin;
        int evMax;
        float evStep;
        int sensorOrientation;
    }

    private static final class Pending {
        Lens lens;
        boolean direct;
        boolean wantRaw;
        boolean wantJpeg;
        Look look;
        int orientation;
        Image raw;
        byte[] jpeg;
        TotalCaptureResult result;
        boolean saving;
    }

    private final Context context;
    private final CameraManager cm;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    final List<Lens> lenses = new ArrayList<>();

    // Einstellungen: von der Oberflaeche gesetzt, auf dem Kamera-Thread gelesen.
    volatile int mode = MODE_BOTH;
    volatile Look look;
    volatile int lensIndex;
    volatile boolean direct;
    volatile Integer manualIso;
    volatile Long manualExposure;
    volatile Float manualFocus;
    volatile int awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    volatile int ev;

    // Zustand: nur auf dem Kamera-Thread.
    private HandlerThread thread;
    private Handler handler;
    private SurfaceTexture texture;
    private boolean started;
    private int generation;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private ImageReader rawReader;
    private ImageReader jpegReader;
    private Lens active;
    private boolean activeDirect;
    private Pending pending;
    private long lastInfo;
    private volatile int autoIso = 100;
    private volatile long autoExposure = 10_000_000L;

    CameraController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.listener = listener;
        loadLenses();
    }

    // ---------------------------------------------------------------- Objektive

    private void loadLenses() {
        List<String> ids = new ArrayList<>();
        try {
            ids.addAll(cm.getCameraCharacteristics(LOGICAL_ID).getPhysicalCameraIds());
        } catch (Throwable ignored) {
        }
        if (ids.isEmpty()) ids.add(LOGICAL_ID);
        for (String id : ids) {
            try {
                Lens lens = readLens(id);
                if (lens != null) lenses.add(lens);
            } catch (Throwable ignored) {
            }
        }
        lenses.sort((a, b) -> Float.compare(a.equivalent, b.equivalent));
        // Start mit dem Objektiv, das der Hauptkamera am naechsten kommt (rund 23 mm).
        int best = 0;
        for (int i = 0; i < lenses.size(); i++) {
            if (Math.abs(lenses.get(i).equivalent - 24f) < Math.abs(lenses.get(best).equivalent - 24f)) best = i;
        }
        lensIndex = best;
    }

    private Lens readLens(String id) throws Exception {
        CameraCharacteristics c = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Lens lens = new Lens();
        lens.id = id;

        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focal != null && focal.length > 0 && sensor != null) {
            double diagonal = Math.sqrt(sensor.getWidth() * (double) sensor.getWidth()
                    + sensor.getHeight() * (double) sensor.getHeight());
            lens.equivalent = diagonal > 0 ? (float) (focal[0] * 43.27 / diagonal) : focal[0];
        }
        float[] apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        lens.aperture = apertures != null && apertures.length > 0 ? apertures[0] : 0f;
        lens.label = Math.round(lens.equivalent) + " mm";

        lens.raw = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
        if (lens.raw == null) return null;
        Size jpeg = null;
        Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (jpegSizes != null) {
            for (Size s : jpegSizes) {
                if (s.equals(lens.raw)) jpeg = s;
            }
        }
        lens.jpeg = jpeg != null ? jpeg : largest(jpegSizes);

        Size preview = null;
        Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
        if (previewSizes != null) {
            for (Size s : previewSizes) {
                if (s.getWidth() > 1440 || s.getHeight() > 1080) continue;
                if (s.getWidth() * 3 != s.getHeight() * 4) continue;
                if (preview == null || s.getWidth() > preview.getWidth()) preview = s;
            }
        }
        lens.preview = preview != null ? preview : new Size(1280, 960);

        Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        lens.iso = iso != null ? iso : new Range<>(100, 1600);
        Range<Long> exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        lens.exposure = exposure != null ? exposure : new Range<>(100_000L, 500_000_000L);
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        lens.minFocusDiopters = minFocus != null && minFocus > 0f ? minFocus : 10f;

        Range<Integer> evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        lens.evMin = evRange != null ? evRange.getLower() : 0;
        lens.evMax = evRange != null ? evRange.getUpper() : 0;
        lens.evStep = evStep != null ? evStep.floatValue() : 0f;

        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        lens.sensorOrientation = orientation != null ? orientation : 90;
        return lens;
    }

    private static Size largest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    Lens currentLens() {
        if (lenses.isEmpty()) return null;
        int i = Math.max(0, Math.min(lensIndex, lenses.size() - 1));
        return lenses.get(i);
    }

    // ------------------------------------------------------------- Lebenszyklus

    void start(SurfaceTexture surfaceTexture) {
        if (thread == null) {
            thread = new HandlerThread("camera");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        handler.post(() -> {
            texture = surfaceTexture;
            started = true;
            reopen();
        });
    }

    void stop() {
        if (thread == null) return;
        final HandlerThread t = thread;
        handler.post(() -> {
            started = false;
            closeAll();
            t.quitSafely();
        });
        thread = null;
        handler = null;
    }

    /** Nach Objektiv-, Modus- oder Zugriffswechsel: Kamera neu oeffnen. */
    void restart() {
        Handler h = handler;
        if (h != null) h.post(this::reopen);
    }

    /** Nach geaenderten Aufnahmewerten: Vorschau mit neuen Werten fortsetzen. */
    void refresh() {
        Handler h = handler;
        if (h != null) h.post(this::startPreview);
    }

    void capture(int deviceOrientation) {
        Handler h = handler;
        if (h != null) h.post(() -> doCapture(deviceOrientation));
    }

    private void closeAll() {
        generation++;
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) {
        }
        session = null;
        try {
            if (device != null) device.close();
        } catch (Throwable ignored) {
        }
        device = null;
        if (pending != null && !pending.saving) {
            if (pending.raw != null) pending.raw.close();
            pending = null;
            busy(false);
        }
        // Reader erst schliessen, wenn kein Bild mehr gespeichert wird.
        if (pending == null) {
            closeReaders();
        }
        try {
            if (previewSurface != null) previewSurface.release();
        } catch (Throwable ignored) {
        }
        previewSurface = null;
    }

    private void closeReaders() {
        try {
            if (rawReader != null) rawReader.close();
        } catch (Throwable ignored) {
        }
        rawReader = null;
        try {
            if (jpegReader != null) jpegReader.close();
        } catch (Throwable ignored) {
        }
        jpegReader = null;
    }

    private void reopen() {
        if (pending != null && pending.saving) {
            // Laufendes Speichern abwarten, dann erneut versuchen.
            handler.postDelayed(this::reopen, 150);
            return;
        }
        closeAll();
        if (!started || texture == null) return;
        active = currentLens();
        if (active == null) {
            status("Keine Kamera mit RAW gefunden");
            return;
        }
        activeDirect = direct;
        final int gen = generation;
        String id = activeDirect ? active.id : LOGICAL_ID;
        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (gen != generation) {
                        d.close();
                        return;
                    }
                    device = d;
                    createSession(gen);
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (gen == generation) device = null;
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    if (gen == generation) {
                        device = null;
                        session = null;
                        status("Kamerafehler " + error + ". Objektiv oder Modus erneut waehlen.");
                    }
                }
            }, handler);
        } catch (SecurityException e) {
            status("Kamera-Berechtigung fehlt");
        } catch (Throwable t) {
            status("Kamera laesst sich nicht oeffnen: " + t.getMessage());
        }
    }

    private void createSession(final int gen) {
        try {
            Lens lens = active;
            texture.setDefaultBufferSize(lens.preview.getWidth(), lens.preview.getHeight());
            previewSurface = new Surface(texture);

            int m = mode;
            boolean wantRaw = m != MODE_LOOK;
            boolean wantJpeg = m != MODE_RAW;

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(output(previewSurface, lens));
            if (wantRaw) {
                rawReader = ImageReader.newInstance(lens.raw.getWidth(), lens.raw.getHeight(),
                        ImageFormat.RAW_SENSOR, 2);
                rawReader.setOnImageAvailableListener(this::onRawImage, handler);
                outputs.add(output(rawReader.getSurface(), lens));
            }
            if (wantJpeg) {
                jpegReader = ImageReader.newInstance(lens.jpeg.getWidth(), lens.jpeg.getHeight(),
                        ImageFormat.JPEG, 2);
                jpegReader.setOnImageAvailableListener(this::onJpegImage, handler);
                outputs.add(output(jpegReader.getSurface(), lens));
            }

            final Handler h = handler;
            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    h::post,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (gen != generation) return;
                            session = s;
                            status("");
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            if (gen != generation) return;
                            status("Diese Kombination aus Objektiv und Modus lehnt das Geraet ab");
                        }
                    });
            device.createCaptureSession(config);
        } catch (Throwable t) {
            status("Kamera-Einrichtung fehlgeschlagen: " + t.getMessage());
        }
    }

    private OutputConfiguration output(Surface surface, Lens lens) {
        OutputConfiguration config = new OutputConfiguration(surface);
        if (!activeDirect && !LOGICAL_ID.equals(lens.id)) config.setPhysicalCameraId(lens.id);
        return config;
    }

    // ------------------------------------------------------------ Aufnahmewerte

    private void applySettings(CaptureRequest.Builder builder, Lens lens) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        Integer iso = manualIso;
        Long exposure = manualExposure;
        if (iso == null && exposure == null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            int steps = Math.max(lens.evMin, Math.min(lens.evMax, ev));
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
        } else {
            // Ein Wert von Hand: der andere wird so gewaehlt, dass die zuletzt gemessene
            // Helligkeit erhalten bleibt.
            double light = (double) autoIso * (double) autoExposure;
            int isoValue;
            long exposureValue;
            if (iso != null && exposure != null) {
                isoValue = iso;
                exposureValue = exposure;
            } else if (iso != null) {
                isoValue = iso;
                exposureValue = (long) (light / Math.max(1, iso));
            } else {
                exposureValue = exposure;
                isoValue = (int) Math.round(light / Math.max(1L, exposure));
            }
            isoValue = Math.max(lens.iso.getLower(), Math.min(lens.iso.getUpper(), isoValue));
            exposureValue = Math.max(lens.exposure.getLower(), Math.min(lens.exposure.getUpper(), exposureValue));
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureValue);
        }

        Float focus = manualFocus;
        if (focus == null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
        }

        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
    }

    private void startPreview() {
        if (session == null || device == null || previewSurface == null || active == null) return;
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            applySettings(builder, active);
            final Lens lens = active;
            session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    onPreviewResult(lens, result);
                }
            }, handler);
        } catch (Throwable t) {
            status("Vorschau fehlgeschlagen: " + t.getMessage());
        }
    }

    private void onPreviewResult(Lens lens, TotalCaptureResult result) {
        CaptureResult source = physicalResult(result, lens);
        Integer iso = source.get(CaptureResult.SENSOR_SENSITIVITY);
        Long exposure = source.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (iso == null) iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (exposure == null) exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (iso == null || exposure == null) return;
        if (manualIso == null && manualExposure == null) {
            autoIso = iso;
            autoExposure = exposure;
        }
        long now = System.currentTimeMillis();
        if (now - lastInfo < 250) return;
        lastInfo = now;
        final String text = "ISO " + iso + "  ·  " + formatExposure(exposure) + "  ·  " + lens.label
                + (lens.aperture > 0 ? String.format(Locale.US, "  ·  f/%.1f", lens.aperture) : "");
        ui.post(() -> listener.onInfo(text));
    }

    private CaptureResult physicalResult(TotalCaptureResult result, Lens lens) {
        if (activeDirect) return result;
        try {
            Map<String, TotalCaptureResult> physical = result.getPhysicalCameraTotalResults();
            TotalCaptureResult r = physical != null ? physical.get(lens.id) : null;
            if (r != null) return r;
        } catch (Throwable ignored) {
        }
        return result;
    }

    static String formatExposure(long nanos) {
        double seconds = nanos / 1e9;
        if (seconds >= 0.3) return String.format(Locale.US, "%.1f s", seconds);
        return "1/" + Math.round(1.0 / seconds) + " s";
    }

    // ----------------------------------------------------------------- Aufnahme

    private void doCapture(int deviceOrientation) {
        if (session == null || device == null || active == null) {
            status("Kamera nicht bereit");
            return;
        }
        if (pending != null) return;
        try {
            final Lens lens = active;
            final Pending p = new Pending();
            p.lens = lens;
            p.direct = activeDirect;
            p.wantRaw = rawReader != null;
            p.wantJpeg = jpegReader != null;
            p.look = look;
            p.orientation = (lens.sensorOrientation + deviceOrientation + 360) % 360;

            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            applySettings(builder, lens);
            if (p.wantRaw) {
                builder.addTarget(rawReader.getSurface());
                // Master-RAW: keine Eingriffe ins Sensorbild, keine Korrekturtabellen in der Datei.
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF);
                builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF);
            }
            if (p.wantJpeg) {
                builder.addTarget(jpegReader.getSurface());
                builder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
                builder.set(CaptureRequest.JPEG_ORIENTATION, p.orientation);
            }

            pending = p;
            busy(true);
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    p.result = result;
                    maybeFinish();
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                            CaptureFailure failure) {
                    abort(p, "Aufnahme fehlgeschlagen (Grund " + failure.getReason() + ")");
                }
            }, handler);
            handler.postDelayed(() -> abort(p, "Aufnahme: keine Antwort der Kamera"), 20000);
        } catch (Throwable t) {
            if (pending != null && !pending.saving) pending = null;
            busy(false);
            status("Aufnahme fehlgeschlagen: " + t.getMessage());
        }
    }

    private void abort(Pending p, String message) {
        if (pending != p || p.saving) return;
        if (p.raw != null) p.raw.close();
        pending = null;
        busy(false);
        status(message);
    }

    private void onRawImage(ImageReader reader) {
        Image image;
        try {
            image = reader.acquireNextImage();
        } catch (Throwable t) {
            return;
        }
        if (image == null) return;
        Pending p = pending;
        if (p != null && p.wantRaw && p.raw == null && !p.saving) {
            p.raw = image;
            maybeFinish();
        } else {
            image.close();
        }
    }

    private void onJpegImage(ImageReader reader) {
        Image image;
        try {
            image = reader.acquireNextImage();
        } catch (Throwable t) {
            return;
        }
        if (image == null) return;
        try {
            Pending p = pending;
            if (p != null && p.wantJpeg && p.jpeg == null && !p.saving) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                p.jpeg = data;
            }
        } finally {
            image.close();
        }
        maybeFinish();
    }

    private void maybeFinish() {
        final Pending p = pending;
        if (p == null || p.saving) return;
        if (p.result == null) return;
        if (p.wantRaw && p.raw == null) return;
        if (p.wantJpeg && p.jpeg == null) return;
        p.saving = true;
        final Handler h = handler;
        saver.execute(() -> {
            final String message = save(p);
            Runnable done = () -> {
                if (pending == p) pending = null;
                busy(false);
                status(message);
            };
            if (h != null && h.getLooper().getThread().isAlive()) {
                h.post(done);
            } else {
                // Kamera-Thread ist bereits beendet (App im Hintergrund): Zustand hier freigeben.
                ui.post(() -> {
                    if (pending == p) pending = null;
                    listener.onBusy(false);
                    listener.onStatus(message);
                });
            }
        });
    }

    /** Laeuft auf dem Speicher-Thread. Liefert die Meldung fuer die Oberflaeche. */
    private String save(Pending p) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String base = "X15U_" + stamp + "_" + Math.round(p.lens.equivalent) + "mm";
        StringBuilder message = new StringBuilder();
        if (p.wantRaw) {
            try {
                CameraCharacteristics characteristics = cm.getCameraCharacteristics(p.lens.id);
                TotalCaptureResult physical = null;
                if (!p.direct) {
                    Map<String, TotalCaptureResult> map = p.result.getPhysicalCameraTotalResults();
                    if (map != null) physical = map.get(p.lens.id);
                }
                Saver.saveDng(context, base + ".dng", p.raw, characteristics, physical, p.result,
                        exifOrientation(p.orientation));
                message.append("RAW gespeichert");
            } catch (Throwable t) {
                message.append("RAW FEHLER: ").append(t.getMessage());
            } finally {
                p.raw.close();
                p.raw = null;
            }
        }
        if (p.wantJpeg) {
            if (message.length() > 0) message.append("  ·  ");
            try {
                Look l = p.look;
                String suffix = l == null || l.identity ? "" : "_" + l.name.replace(' ', '-');
                Saver.saveJpeg(context, base + suffix + ".jpg", p.jpeg, l);
                message.append("JPEG gespeichert").append(l == null || l.identity ? "" : " (" + l.name + ")");
            } catch (Throwable t) {
                message.append("JPEG FEHLER: ").append(t.getMessage());
            }
            p.jpeg = null;
        }
        return message.toString();
    }

    private static int exifOrientation(int degrees) {
        switch (degrees) {
            case 90:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private void status(String text) {
        ui.post(() -> listener.onStatus(text));
    }

    private void busy(boolean value) {
        ui.post(() -> listener.onBusy(value));
    }
}
