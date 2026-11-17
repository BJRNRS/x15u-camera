package app.x15u.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraMetadata;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CameraController.Listener {

    private static final int P_EV = 0;
    private static final int P_ISO = 1;
    private static final int P_TIME = 2;
    private static final int P_FOCUS = 3;
    private static final int P_WB = 4;
    private static final String[] PARAM_NAMES = {"EV", "ISO", "ZEIT", "FOKUS", "WB"};

    private static final int[] WB_MODES = {
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_SHADE,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
    };
    private static final String[] WB_NAMES = {
            "Auto", "Tageslicht", "Wolkig", "Schatten", "Kunstlicht", "Leuchtstoff"};

    private static final String[] MODE_NAMES = {"RAW", "LOOK", "RAW + LOOK"};
    private static final int SEEK_MAX = 1000;
    private static final long SHORTEST_EXPOSURE = 125_000L; // 1/8000 s

    private CameraController controller;
    private Look[] looks;
    private int lookIndex;
    private int wbIndex;
    private int param = P_EV;
    private int deviceOrientation;
    private boolean busy;
    private boolean askedPermission;
    private boolean updatingSeek;

    private TextureView preview;
    private TextView info;
    private TextView status;
    private TextView valueLabel;
    private TextView autoChip;
    private TextView directChip;
    private SeekBar seek;
    private View shutter;
    private View lookScroll;
    private TextView[] modeChips;
    private TextView[] lensChips;
    private TextView[] lookChips;
    private TextView[] paramChips;

    private OrientationEventListener orientationListener;
    private MediaActionSound sound;

    /** Haelt die Vorschau im Seitenverhaeltnis 3:4 (Hochformat). */
    private static final class AspectFrame extends FrameLayout {
        AspectFrame(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = width * 4 / 3;
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        controller = new CameraController(this, this);
        looks = Look.all();
        controller.look = looks[0];
        sound = new MediaActionSound();
        try {
            sound.load(MediaActionSound.SHUTTER_CLICK);
        } catch (Throwable ignored) {
        }

        buildUi();

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) return;
                deviceOrientation = ((degrees + 45) / 90 * 90) % 360;
            }
        };
    }

    // -------------------------------------------------------------- Oberflaeche

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);

        // Vorschau mit Werten oben und Meldungen unten.
        AspectFrame frame = new AspectFrame(this);
        preview = new TextureView(this);
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        info = overlayText();
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        infoParams.topMargin = dp(8);
        frame.addView(info, infoParams);

        status = overlayText();
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusParams.bottomMargin = dp(8);
        frame.addView(status, statusParams);
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Modus: RAW, LOOK oder beides. Rechts der Umschalter fuer den Kamerazugriff.
        LinearLayout modeRow = row();
        modeChips = new TextView[MODE_NAMES.length];
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int index = i;
            modeChips[i] = chip(MODE_NAMES[i]);
            modeChips[i].setOnClickListener(v -> selectMode(index));
            modeRow.addView(modeChips[i]);
        }
        View spacer = new View(this);
        modeRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        directChip = chip("Zugriff: Standard");
        directChip.setOnClickListener(v -> toggleDirect());
        modeRow.addView(directChip);
        root.addView(modeRow);

        // Looks.
        LinearLayout lookRow = row();
        lookChips = new TextView[looks.length];
        for (int i = 0; i < looks.length; i++) {
            final int index = i;
            lookChips[i] = chip(looks[i].name);
            lookChips[i].setOnClickListener(v -> selectLook(index));
            lookRow.addView(lookChips[i]);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(lookRow);
        lookScroll = scroll;
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Objektive.
        LinearLayout lensRow = row();
        lensRow.setGravity(Gravity.CENTER_HORIZONTAL);
        List<CameraController.Lens> lenses = controller.lenses;
        lensChips = new TextView[lenses.size()];
        for (int i = 0; i < lenses.size(); i++) {
            final int index = i;
            lensChips[i] = chip(lenses.get(i).label);
            lensChips[i].setOnClickListener(v -> selectLens(index));
            lensRow.addView(lensChips[i]);
        }
        root.addView(lensRow);

        // Aufnahmewerte.
        LinearLayout paramRow = row();
        paramRow.setGravity(Gravity.CENTER_HORIZONTAL);
        paramChips = new TextView[PARAM_NAMES.length];
        for (int i = 0; i < PARAM_NAMES.length; i++) {
            final int index = i;
            paramChips[i] = chip(PARAM_NAMES[i]);
            paramChips[i].setOnClickListener(v -> selectParam(index));
            paramRow.addView(paramChips[i]);
        }
        autoChip = chip("AUTO");
        autoChip.setOnClickListener(v -> resetParam());
        paramRow.addView(autoChip);
        root.addView(paramRow);

        LinearLayout seekRow = row();
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seek.setMax(SEEK_MAX);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !updatingSeek) onSeek(progress / (float) SEEK_MAX);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        valueLabel = new TextView(this);
        valueLabel.setTextColor(0xFFFFFFFF);
        valueLabel.setTextSize(14);
        valueLabel.setGravity(Gravity.CENTER);
        seekRow.addView(valueLabel, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(seekRow);

        // Ausloeser.
        FrameLayout shutterRow = new FrameLayout(this);
        shutter = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFFFFFFF);
        circle.setStroke(dp(4), 0xFF8A8A8A);
        shutter.setBackground(circle);
        shutter.setOnClickListener(v -> onShutter());
        shutterRow.addView(shutter, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        root.addView(shutterRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                startIfReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                controller.stop();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        refreshChips();
        syncSeek();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(6), dp(2), dp(6), dp(2));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(params);
        style(t, false);
        return t;
    }

    private void style(TextView t, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(16));
        background.setColor(selected ? 0xFFEDEDED : 0xFF2B2B2B);
        t.setBackground(background);
        t.setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
    }

    private TextView overlayText() {
        TextView t = new TextView(this);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshChips() {
        for (int i = 0; i < modeChips.length; i++) style(modeChips[i], i == controller.mode);
        for (int i = 0; i < lensChips.length; i++) style(lensChips[i], i == controller.lensIndex);
        for (int i = 0; i < lookChips.length; i++) style(lookChips[i], i == lookIndex);
        for (int i = 0; i < paramChips.length; i++) style(paramChips[i], i == param);
        style(directChip, controller.direct);
        directChip.setText(controller.direct ? "Zugriff: Direkt" : "Zugriff: Standard");
        lookScroll.setVisibility(controller.mode == CameraController.MODE_RAW ? View.INVISIBLE : View.VISIBLE);
        updatePreviewFilter();
    }

    /** Zeigt den gewaehlten Look naeherungsweise schon in der Vorschau. */
    private void updatePreviewFilter() {
        Look look = looks[lookIndex];
        if (controller.mode == CameraController.MODE_RAW || look.identity) {
            preview.setLayerPaint(null);
            return;
        }
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(LookEngine.previewMatrix(look)));
        preview.setLayerPaint(paint);
    }

    // ----------------------------------------------------------------- Auswahl

    private void selectMode(int index) {
        if (controller.mode == index) return;
        controller.mode = index;
        refreshChips();
        controller.restart();
    }

    private void selectLook(int index) {
        lookIndex = index;
        controller.look = looks[index];
        refreshChips();
    }

    private void selectLens(int index) {
        if (controller.lensIndex == index) return;
        controller.lensIndex = index;
        controller.manualFocus = null;
        refreshChips();
        syncSeek();
        controller.restart();
    }

    private void toggleDirect() {
        controller.direct = !controller.direct;
        refreshChips();
        controller.restart();
    }

    private void selectParam(int index) {
        param = index;
        refreshChips();
        syncSeek();
    }

    private void resetParam() {
        switch (param) {
            case P_EV:
                controller.ev = 0;
                break;
            case P_ISO:
                controller.manualIso = null;
                break;
            case P_TIME:
                controller.manualExposure = null;
                break;
            case P_FOCUS:
                controller.manualFocus = null;
                break;
            default:
                wbIndex = 0;
                controller.awbMode = WB_MODES[0];
                break;
        }
        syncSeek();
        controller.refresh();
    }

    // ------------------------------------------------------------------ Regler

    private void onSeek(float t) {
        CameraController.Lens lens = controller.currentLens();
        if (lens == null) return;
        switch (param) {
            case P_EV:
                controller.ev = lens.evMin + Math.round(t * (lens.evMax - lens.evMin));
                break;
            case P_ISO: {
                double lo = lens.iso.getLower();
                double hi = lens.iso.getUpper();
                controller.manualIso = (int) Math.round(lo * Math.pow(hi / lo, t));
                break;
            }
            case P_TIME: {
                double lo = Math.max(lens.exposure.getLower(), SHORTEST_EXPOSURE);
                double hi = Math.max(lo * 2, lens.exposure.getUpper());
                controller.manualExposure = (long) (lo * Math.pow(hi / lo, t));
                break;
            }
            case P_FOCUS:
                controller.manualFocus = lens.minFocusDiopters * t * t;
                break;
            default:
                wbIndex = Math.round(t * (WB_MODES.length - 1));
                controller.awbMode = WB_MODES[wbIndex];
                break;
        }
        updateValueLabel();
        controller.refresh();
    }

    /** Stellt den Regler auf den aktuellen Wert der gewaehlten Groesse. */
    private void syncSeek() {
        CameraController.Lens lens = controller.currentLens();
        float t = 0.5f;
        if (lens != null) {
            switch (param) {
                case P_EV:
                    t = lens.evMax > lens.evMin
                            ? (controller.ev - lens.evMin) / (float) (lens.evMax - lens.evMin) : 0.5f;
                    break;
                case P_ISO: {
                    Integer iso = controller.manualIso;
                    if (iso != null) {
                        double lo = lens.iso.getLower();
                        double hi = lens.iso.getUpper();
                        t = (float) (Math.log(iso / lo) / Math.log(hi / lo));
                    }
                    break;
                }
                case P_TIME: {
                    Long exposure = controller.manualExposure;
                    if (exposure != null) {
                        double lo = Math.max(lens.exposure.getLower(), SHORTEST_EXPOSURE);
                        double hi = Math.max(lo * 2, lens.exposure.getUpper());
                        t = (float) (Math.log(exposure / lo) / Math.log(hi / lo));
                    }
                    break;
                }
                case P_FOCUS: {
                    Float focus = controller.manualFocus;
                    t = focus != null ? (float) Math.sqrt(focus / lens.minFocusDiopters) : 0f;
                    break;
                }
                default:
                    t = wbIndex / (float) (WB_MODES.length - 1);
                    break;
            }
        }
        t = Math.max(0f, Math.min(1f, t));
        updatingSeek = true;
        seek.setProgress(Math.round(t * SEEK_MAX));
        updatingSeek = false;
        updateValueLabel();
    }

    private void updateValueLabel() {
        CameraController.Lens lens = controller.currentLens();
        String text;
        boolean auto;
        switch (param) {
            case P_EV: {
                float step = lens != null ? lens.evStep : 0f;
                auto = controller.ev == 0;
                text = String.format(Locale.US, "%+.1f EV", controller.ev * step);
                break;
            }
            case P_ISO: {
                Integer iso = controller.manualIso;
                auto = iso == null;
                text = auto ? "Auto" : "ISO " + iso;
                break;
            }
            case P_TIME: {
                Long exposure = controller.manualExposure;
                auto = exposure == null;
                text = auto ? "Auto" : CameraController.formatExposure(exposure);
                break;
            }
            case P_FOCUS: {
                Float focus = controller.manualFocus;
                auto = focus == null;
                if (auto) {
                    text = "Autofokus";
                } else if (focus < 0.05f) {
                    text = "Unendlich";
                } else {
                    text = String.format(Locale.US, "%.2f m", 1f / focus);
                }
                break;
            }
            default:
                auto = wbIndex == 0;
                text = WB_NAMES[wbIndex];
                break;
        }
        valueLabel.setText(text);
        style(autoChip, auto);
    }

    // ----------------------------------------------------------------- Aufnahme

    private void onShutter() {
        if (busy) return;
        try {
            sound.play(MediaActionSound.SHUTTER_CLICK);
        } catch (Throwable ignored) {
        }
        controller.capture(deviceOrientation);
    }

    @Override
    public void onInfo(String text) {
        info.setText(text);
    }

    @Override
    public void onStatus(String text) {
        status.setText(text);
    }

    @Override
    public void onBusy(boolean value) {
        busy = value;
        shutter.setAlpha(value ? 0.35f : 1f);
    }

    // ------------------------------------------------------------- Lebenszyklus

    private void startIfReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            } else {
                status.setText("Ohne Kamera-Berechtigung geht es nicht");
            }
            return;
        }
        if (preview.isAvailable()) controller.start(preview.getSurfaceTexture());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startIfReady();
        } else {
            status.setText("Ohne Kamera-Berechtigung geht es nicht");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        orientationListener.enable();
        startIfReady();
    }

    @Override
    protected void onPause() {
        orientationListener.disable();
        controller.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            sound.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
