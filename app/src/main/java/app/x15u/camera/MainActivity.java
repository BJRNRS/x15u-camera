package app.x15u.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CameraController.Listener {

    private static final int P_NONE = -1;
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
    private static final String[] WB_SHORT = {"Auto", "Tag", "Wolke", "Schatten", "Kunst", "Leucht"};

    private static final String[] MODE_NAMES = {"RAW", "LOOK", "RAW + LOOK"};
    private static final String[] ASPECT_NAMES = {"4:3", "3:2", "16:9", "1:1"};
    private static final String[] ASPECT_MP = {"12,6 MP", "11,2 MP", "9,4 MP", "9,4 MP"};
    private static final float[] ASPECTS = {4f / 3f, 1.5f, 16f / 9f, 1f};
    private static final int SEEK_MAX = 1000;
    private static final long SHORTEST_EXPOSURE = 125_000L; // 1/8000 s

    private static final int ACCENT = 0xFFFFC94D;
    private static final int PANEL = 0xFF1C1C1E;
    private static final int MUTED = 0xFF8E8E93;

    private CameraController controller;
    private SharedPreferences prefs;
    private Look[] looks;
    private int lookIndex;
    private int wbIndex;
    private int param = P_NONE;
    private int deviceOrientation;
    private boolean busy;
    private boolean askedPermission;
    private boolean updatingSeek;
    private boolean grid;
    private int aspectIndex;

    private TextureView preview;
    private View flash;
    private GridView gridView;
    private CropMask cropMask;
    private FocusRing focusRing;
    private TextView info;
    private TextView status;
    private TextView[] modeChips;
    private TextView[] lensChips;
    private TextView[] paramValues;
    private LinearLayout[] paramCells;
    private LinearLayout sliderRow;
    private SeekBar seek;
    private TextView sliderLabel;
    private TextView autoButton;
    private View shutter;
    private TextView lookButton;

    private FrameLayout lookMenu;
    private ImageView[] lookThumbs;
    private TextView[] lookNames;
    private FrameLayout settingsMenu;
    private TextView directStandard;
    private TextView directDirect;
    private TextView gridToggle;
    private TextView[] aspectChoices;

    private OrientationEventListener orientationListener;

    // ---------------------------------------------------------------- Hilfsviews

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

    /** Drittel-Raster ueber der Vorschau. */
    private static final class GridView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridView(Context context) {
            super(context);
            paint.setColor(0x66FFFFFF);
            paint.setStrokeWidth(context.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(w * i / 3f, 0, w * i / 3f, h, paint);
                canvas.drawLine(0, h * i / 3f, w, h * i / 3f, paint);
            }
        }
    }

    /** Dunkelt die Bereiche ab, die beim gewaehlten Seitenverhaeltnis wegfallen. */
    private static final class CropMask extends View {
        private final Paint paint = new Paint();
        private float ratio = 4f / 3f;

        CropMask(Context context) {
            super(context);
            paint.setColor(0xB0000000);
        }

        void setRatio(float value) {
            ratio = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float current = h / w;
            if (Math.abs(ratio - current) < 0.01f) return;
            if (ratio > current) {
                float keep = h / ratio;
                float bar = (w - keep) / 2f;
                canvas.drawRect(0, 0, bar, h, paint);
                canvas.drawRect(w - bar, 0, w, h, paint);
            } else {
                float keep = w * ratio;
                float bar = (h - keep) / 2f;
                canvas.drawRect(0, 0, w, bar, paint);
                canvas.drawRect(0, h - bar, w, h, paint);
            }
        }
    }

    /** Quadrat am Fokuspunkt: gelb beim Suchen, gruen bei scharf, rot wenn nicht scharf. */
    private static final class FocusRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x;
        private float y;
        private boolean visible;

        FocusRing(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * context.getResources().getDisplayMetrics().density);
        }

        void show(float px, float py, int color) {
            x = px;
            y = py;
            visible = true;
            paint.setColor(color);
            invalidate();
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void hide() {
            visible = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!visible) return;
            float r = 34f * getResources().getDisplayMetrics().density;
            canvas.drawRect(x - r, y - r, x + r, y + r, paint);
        }
    }

    // -------------------------------------------------------------- Lebenszyklus

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("x15u", MODE_PRIVATE);
        controller = new CameraController(this, this);
        looks = Look.all();
        restorePrefs();

        buildUi();

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) return;
                deviceOrientation = ((degrees + 45) / 90 * 90) % 360;
            }
        };
    }

    private void restorePrefs() {
        int mode = prefs.getInt("mode", CameraController.MODE_BOTH);
        controller.mode = Math.max(0, Math.min(2, mode));
        String lookName = prefs.getString("look", looks[0].name);
        lookIndex = 0;
        for (int i = 0; i < looks.length; i++) {
            if (looks[i].name.equals(lookName)) lookIndex = i;
        }
        controller.look = looks[lookIndex];
        int lens = prefs.getInt("lens", -1);
        if (lens >= 0 && lens < controller.lenses.size()) controller.lensIndex = lens;
        controller.direct = prefs.getBoolean("direct", false);
        grid = prefs.getBoolean("grid", false);
        aspectIndex = Math.max(0, Math.min(ASPECTS.length - 1, prefs.getInt("aspect", 0)));
        controller.aspect = ASPECTS[aspectIndex];
    }

    private void savePrefs() {
        prefs.edit()
                .putInt("mode", controller.mode)
                .putString("look", looks[lookIndex].name)
                .putInt("lens", controller.lensIndex)
                .putBoolean("direct", controller.direct)
                .putBoolean("grid", grid)
                .putInt("aspect", aspectIndex)
                .apply();
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
        savePrefs();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (lookMenu.getVisibility() == View.VISIBLE) {
            closeLookMenu();
        } else if (settingsMenu.getVisibility() == View.VISIBLE) {
            settingsMenu.setVisibility(View.GONE);
        } else if (param != P_NONE) {
            selectParam(param);
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------- Hauptoberflaeche

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFitsSystemWindows(true);
        root.addView(content, match());

        content.addView(buildModeBar());
        content.addView(buildPreview(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(buildLensRow());
        content.addView(buildParamRow());
        content.addView(buildSliderRow());
        content.addView(buildBottomBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        lookMenu = buildLookMenu();
        root.addView(lookMenu, match());
        settingsMenu = buildSettingsMenu();
        root.addView(settingsMenu, match());

        setContentView(root);
        refreshAll();
    }

    /** Oben: RAW, LOOK oder beides als Segment-Schalter. */
    private View buildModeBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(0, dp(8), 0, dp(8));

        LinearLayout segment = new LinearLayout(this);
        segment.setBackground(rounded(PANEL, 18));
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        modeChips = new TextView[MODE_NAMES.length];
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int index = i;
            TextView t = text(MODE_NAMES[i], 13, 0xFFFFFFFF);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(16), dp(6), dp(16), dp(6));
            t.setOnClickListener(v -> selectMode(index));
            modeChips[i] = t;
            segment.addView(t);
        }
        bar.addView(segment);
        return bar;
    }

    private View buildPreview() {
        AspectFrame frame = new AspectFrame(this);
        preview = new TextureView(this);
        frame.addView(preview, match());

        cropMask = new CropMask(this);
        frame.addView(cropMask, match());

        gridView = new GridView(this);
        frame.addView(gridView, match());

        focusRing = new FocusRing(this);
        frame.addView(focusRing, match());

        flash = new View(this);
        flash.setBackgroundColor(0xFF000000);
        flash.setAlpha(0f);
        frame.addView(flash, match());

        info = overlayText(12);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        infoParams.topMargin = dp(6);
        frame.addView(info, infoParams);

        status = overlayText(13);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusParams.bottomMargin = dp(8);
        frame.addView(status, statusParams);

        final GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                onPreviewTap(e.getX(), e.getY(), frame.getWidth(), frame.getHeight());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetFocusPoint();
                return true;
            }
        });
        frame.setOnTouchListener((v, event) -> taps.onTouchEvent(event));

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
        return frame;
    }

    /** Objektive als runde Knoepfe. */
    private View buildLensRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(10), 0, dp(4));
        List<CameraController.Lens> lenses = controller.lenses;
        lensChips = new TextView[lenses.size()];
        for (int i = 0; i < lenses.size(); i++) {
            final int index = i;
            TextView t = text(String.valueOf(Math.round(lenses.get(i).equivalent)), 13, 0xFFFFFFFF);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(42));
            params.setMargins(dp(8), 0, dp(8), 0);
            t.setLayoutParams(params);
            t.setOnClickListener(v -> selectLens(index));
            lensChips[i] = t;
            row.addView(t);
        }
        return row;
    }

    /** Aufnahmewerte: Name klein, aktueller Wert gross. Antippen oeffnet den Regler. */
    private View buildParamRow() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(8), dp(6), dp(8), dp(2));
        paramCells = new LinearLayout[PARAM_NAMES.length];
        paramValues = new TextView[PARAM_NAMES.length];
        for (int i = 0; i < PARAM_NAMES.length; i++) {
            final int index = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(6), 0, dp(6));
            TextView label = text(PARAM_NAMES[i], 10, MUTED);
            TextView value = text("", 14, 0xFFFFFFFF);
            value.setSingleLine(true);
            cell.addView(label);
            cell.addView(value);
            cell.setOnClickListener(v -> selectParam(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(2), 0, dp(2), 0);
            row.addView(cell, params);
            paramCells[i] = cell;
            paramValues[i] = value;
        }
        return row;
    }

    private View buildSliderRow() {
        sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(dp(12), 0, dp(12), 0);

        sliderLabel = text("", 13, ACCENT);
        sliderRow.addView(sliderLabel, new LinearLayout.LayoutParams(dp(84),
                ViewGroup.LayoutParams.WRAP_CONTENT));

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
        sliderRow.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        autoButton = text("AUTO", 12, 0xFFFFFFFF);
        autoButton.setTypeface(Typeface.DEFAULT_BOLD);
        autoButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        autoButton.setOnClickListener(v -> resetParam());
        sliderRow.addView(autoButton);
        sliderRow.setVisibility(View.GONE);
        return sliderRow;
    }

    /** Unten: Look-Menue links, Ausloeser Mitte, Optionen rechts. */
    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), 0, dp(20), 0);

        lookButton = text("", 13, 0xFFFFFFFF);
        lookButton.setTypeface(Typeface.DEFAULT_BOLD);
        lookButton.setSingleLine(true);
        lookButton.setBackground(rounded(PANEL, 22));
        lookButton.setOnClickListener(v -> openLookMenu());
        bar.addView(lookButton, new LinearLayout.LayoutParams(dp(100), dp(44)));

        FrameLayout center = new FrameLayout(this);
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(0xFFFFFFFF);
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(0xFFFFFFFF);
        inner.setStroke(dp(4), 0xFF000000);
        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(outer);
        holder.setPadding(dp(3), dp(3), dp(3), dp(3));
        shutter = new View(this);
        shutter.setBackground(inner);
        holder.addView(shutter, match());
        holder.setOnClickListener(v -> onShutter());
        center.addView(holder, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));
        bar.addView(center, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView options = text("Optionen", 13, 0xFFFFFFFF);
        options.setBackground(rounded(PANEL, 22));
        options.setOnClickListener(v -> settingsMenu.setVisibility(View.VISIBLE));
        bar.addView(options, new LinearLayout.LayoutParams(dp(100), dp(44)));
        return bar;
    }

    // ----------------------------------------------------------------- Look-Menue

    private FrameLayout buildLookMenu() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF2000000);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setFitsSystemWindows(true);
        overlay.addView(column, match());

        column.addView(menuHeader("Looks", this::closeLookMenu));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), 0, dp(12), dp(24));
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int columns = 3;
        int gap = dp(8);
        int tileWidth = (getResources().getDisplayMetrics().widthPixels - dp(24) - gap * (columns - 1)) / columns;
        int tileHeight = tileWidth * 4 / 3;

        lookThumbs = new ImageView[looks.length];
        lookNames = new TextView[looks.length];
        for (String category : Look.CATEGORIES) {
            TextView title = text(categoryLabel(category), 12, MUTED);
            title.setGravity(Gravity.START);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(dp(2), dp(16), 0, dp(8));
            list.addView(title);

            LinearLayout row = null;
            int inRow = 0;
            for (int i = 0; i < looks.length; i++) {
                if (!looks[i].category.equals(category)) continue;
                if (row == null || inRow == columns) {
                    row = new LinearLayout(this);
                    list.addView(row);
                    inRow = 0;
                }
                final int index = i;
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackgroundColor(0xFF2C2C2E);
                thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
                tile.addView(thumb, new LinearLayout.LayoutParams(tileWidth, tileHeight));
                TextView name = text(looks[i].name, 12, 0xFFFFFFFF);
                name.setPadding(0, dp(4), 0, dp(10));
                tile.addView(name, new LinearLayout.LayoutParams(tileWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                tile.setOnClickListener(v -> {
                    selectLook(index);
                    closeLookMenu();
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(inRow == 0 ? 0 : gap, 0, 0, 0);
                row.addView(tile, params);
                lookThumbs[i] = thumb;
                lookNames[i] = name;
                inRow++;
            }
        }
        return overlay;
    }

    private static String categoryLabel(String category) {
        switch (category) {
            case Look.NATURAL:
                return "NATÜRLICH";
            case Look.COLOR:
                return "FARBE";
            case Look.FILM:
                return "FILM";
            default:
                return "SCHWARZWEISS";
        }
    }

    private void openLookMenu() {
        if (controller.mode == CameraController.MODE_RAW) {
            status.setText("Im RAW-Modus gibt es keine Looks");
            return;
        }
        refreshLookMenu();
        lookMenu.setVisibility(View.VISIBLE);
        // Vorschaubilder aus dem aktuellen Kamerabild berechnen.
        final Bitmap base = preview.isAvailable() ? preview.getBitmap(240, 320) : null;
        if (base == null) return;
        new Thread(() -> {
            for (int i = 0; i < looks.length; i++) {
                final int index = i;
                final Bitmap copy = base.copy(Bitmap.Config.ARGB_8888, true);
                LookEngine.apply(copy, looks[i]);
                runOnUiThread(() -> lookThumbs[index].setImageBitmap(copy));
            }
        }).start();
    }

    private void closeLookMenu() {
        lookMenu.setVisibility(View.GONE);
    }

    private void refreshLookMenu() {
        for (int i = 0; i < looks.length; i++) {
            boolean selected = i == lookIndex;
            lookThumbs[i].setBackgroundColor(selected ? ACCENT : 0xFF2C2C2E);
            lookNames[i].setTextColor(selected ? ACCENT : 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------- Optionen

    private FrameLayout buildSettingsMenu() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF2000000);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setFitsSystemWindows(true);
        overlay.addView(column, match());
        column.addView(menuHeader("Optionen", () -> settingsMenu.setVisibility(View.GONE)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(20));
        column.addView(body);

        body.addView(sectionTitle("FORMAT (LOOK-JPEG)"));
        LinearLayout aspectRow = new LinearLayout(this);
        aspectChoices = new TextView[ASPECTS.length];
        for (int i = 0; i < ASPECTS.length; i++) {
            final int index = i;
            TextView t = choice(ASPECT_NAMES[i] + "\n" + ASPECT_MP[i]);
            t.setTextSize(13);
            t.setOnClickListener(v -> selectAspect(index));
            if (i > 0) aspectRow.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
            aspectRow.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            aspectChoices[i] = t;
        }
        body.addView(aspectRow);
        TextView aspectNote = text("4:3 nutzt die volle Sensorfläche mit 12,6 MP, das Maximum, das Xiaomi "
                + "fremden Apps freigibt. Die anderen Formate sind mittige Ausschnitte daraus. "
                + "RAW bleibt immer 4:3.", 13, MUTED);
        aspectNote.setGravity(Gravity.START);
        aspectNote.setPadding(0, dp(10), 0, 0);
        body.addView(aspectNote);

        body.addView(sectionTitle("RASTER"));
        gridToggle = choice("");
        gridToggle.setOnClickListener(v -> {
            grid = !grid;
            refreshAll();
        });
        body.addView(gridToggle);

        body.addView(sectionTitle("KAMERAZUGRIFF"));
        LinearLayout choices = new LinearLayout(this);
        directStandard = choice("Standard");
        directStandard.setOnClickListener(v -> setDirect(false));
        directDirect = choice("Direkt");
        directDirect.setOnClickListener(v -> setDirect(true));
        choices.addView(directStandard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        choices.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        choices.addView(directDirect, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(choices);

        TextView explain = text("Standard ist der von Xiaomi für fremde Apps vorgesehene und getestete Weg: "
                + "Die App öffnet eine gemeinsame Kamera und wählt darüber das Objektiv.\n\n"
                + "Direkt öffnet das gewählte Objektiv als eigene Kamera. Nur als Ausweichlösung gedacht, "
                + "falls ein Objektiv im Standard-Zugriff Probleme macht.", 13, MUTED);
        explain.setGravity(Gravity.START);
        explain.setPadding(0, dp(12), 0, 0);
        body.addView(explain);

        TextView storage = text("Bilder werden unter DCIM/X15U gespeichert.", 13, MUTED);
        storage.setGravity(Gravity.START);
        storage.setPadding(0, dp(24), 0, 0);
        body.addView(storage);
        return overlay;
    }

    private void selectAspect(int index) {
        aspectIndex = index;
        controller.aspect = ASPECTS[index];
        refreshAll();
    }

    private void setDirect(boolean value) {
        if (controller.direct == value) return;
        controller.direct = value;
        refreshAll();
        controller.restart();
    }

    private View menuHeader(String title, Runnable close) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(12), dp(12), dp(4));
        TextView t = text(title, 20, 0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = text("Fertig", 14, ACCENT);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setPadding(dp(12), dp(8), dp(12), dp(8));
        done.setOnClickListener(v -> close.run());
        header.addView(done);
        return header;
    }

    private TextView sectionTitle(String title) {
        TextView t = text(title, 12, MUTED);
        t.setGravity(Gravity.START);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(8));
        return t;
    }

    private TextView choice(String label) {
        TextView t = text(label, 14, 0xFFFFFFFF);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        return t;
    }

    // ------------------------------------------------------------------- Anzeige

    private void refreshAll() {
        for (int i = 0; i < modeChips.length; i++) {
            boolean selected = i == controller.mode;
            modeChips[i].setBackground(selected ? rounded(0xFFFFFFFF, 15) : null);
            modeChips[i].setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
        }
        for (int i = 0; i < lensChips.length; i++) {
            boolean selected = i == controller.lensIndex;
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(selected ? 0xFFFFFFFF : PANEL);
            lensChips[i].setBackground(circle);
            lensChips[i].setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
        }
        for (int i = 0; i < paramCells.length; i++) {
            boolean selected = i == param;
            paramCells[i].setBackground(selected ? rounded(PANEL, 10) : null);
            paramValues[i].setTextColor(selected ? ACCENT : (isManual(i) ? 0xFFFFFFFF : 0xFFAEAEB2));
            paramValues[i].setText(shortValue(i));
        }
        sliderRow.setVisibility(param == P_NONE ? View.GONE : View.VISIBLE);

        boolean raw = controller.mode == CameraController.MODE_RAW;
        lookButton.setText(raw ? "Kein Look" : looks[lookIndex].name);
        lookButton.setAlpha(raw ? 0.4f : 1f);

        gridView.setVisibility(grid ? View.VISIBLE : View.GONE);
        // Ausschnitt nur zeigen, wenn ein JPEG entsteht; RAW ist immer 4:3.
        cropMask.setRatio(raw ? 4f / 3f : ASPECTS[aspectIndex]);
        for (int i = 0; i < aspectChoices.length; i++) {
            boolean selected = i == aspectIndex;
            aspectChoices[i].setBackground(rounded(selected ? 0xFFFFFFFF : PANEL, 12));
            aspectChoices[i].setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
        }
        gridToggle.setText(grid ? "Drittel-Raster: an" : "Drittel-Raster: aus");
        gridToggle.setBackground(rounded(grid ? 0xFF3A3A3C : PANEL, 12));
        directStandard.setBackground(rounded(controller.direct ? PANEL : 0xFFFFFFFF, 12));
        directStandard.setTextColor(controller.direct ? 0xFFFFFFFF : 0xFF000000);
        directDirect.setBackground(rounded(controller.direct ? 0xFFFFFFFF : PANEL, 12));
        directDirect.setTextColor(controller.direct ? 0xFF000000 : 0xFFFFFFFF);
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

    private boolean isManual(int p) {
        switch (p) {
            case P_EV:
                return controller.ev != 0;
            case P_ISO:
                return controller.manualIso != null;
            case P_TIME:
                return controller.manualExposure != null;
            case P_FOCUS:
                return controller.manualFocus != null || controller.focusRegion != null;
            case P_WB:
                return wbIndex != 0;
            default:
                return false;
        }
    }

    private String shortValue(int p) {
        CameraController.Lens lens = controller.currentLens();
        switch (p) {
            case P_EV: {
                float step = lens != null ? lens.evStep : 0f;
                return String.format(Locale.US, "%+.1f", controller.ev * step);
            }
            case P_ISO: {
                Integer iso = controller.manualIso;
                return iso == null ? "Auto" : String.valueOf(iso);
            }
            case P_TIME: {
                Long exposure = controller.manualExposure;
                return exposure == null ? "Auto" : CameraController.formatExposure(exposure).replace(" s", "");
            }
            case P_FOCUS: {
                Float focus = controller.manualFocus;
                if (focus == null) return controller.focusRegion != null ? "Punkt" : "AF";
                if (focus < 0.05f) return "∞";
                return String.format(Locale.US, "%.1f m", 1f / focus);
            }
            default:
                return WB_SHORT[wbIndex];
        }
    }

    private String longValue(int p) {
        switch (p) {
            case P_EV:
                return shortValue(p) + " EV";
            case P_ISO:
                return controller.manualIso == null ? "ISO Auto" : "ISO " + controller.manualIso;
            case P_TIME: {
                Long exposure = controller.manualExposure;
                return exposure == null ? "Zeit Auto" : CameraController.formatExposure(exposure);
            }
            case P_FOCUS: {
                Float focus = controller.manualFocus;
                if (focus == null) return controller.focusRegion != null ? "Fokuspunkt" : "Autofokus";
                if (focus < 0.05f) return "Unendlich";
                return String.format(Locale.US, "%.2f m", 1f / focus);
            }
            default:
                return WB_NAMES[wbIndex];
        }
    }

    // ------------------------------------------------------------------- Auswahl

    private void selectMode(int index) {
        if (controller.mode == index) return;
        controller.mode = index;
        refreshAll();
        controller.restart();
    }

    private void selectLook(int index) {
        lookIndex = index;
        controller.look = looks[index];
        refreshAll();
    }

    private void selectLens(int index) {
        if (controller.lensIndex == index) return;
        controller.lensIndex = index;
        controller.manualFocus = null;
        focusRing.hide();
        refreshAll();
        syncSeek();
        controller.restart();
    }

    /** Antippen oeffnet den Regler, erneutes Antippen schliesst ihn. */
    private void selectParam(int index) {
        param = param == index ? P_NONE : index;
        refreshAll();
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
                resetFocusPoint();
                break;
            case P_WB:
                wbIndex = 0;
                controller.awbMode = WB_MODES[0];
                break;
            default:
                return;
        }
        syncSeek();
        refreshAll();
        controller.refresh();
    }

    // -------------------------------------------------------------------- Regler

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
                controller.focusRegion = null;
                focusRing.hide();
                controller.manualFocus = lens.minFocusDiopters * t * t;
                break;
            case P_WB:
                wbIndex = Math.round(t * (WB_MODES.length - 1));
                controller.awbMode = WB_MODES[wbIndex];
                break;
            default:
                return;
        }
        sliderLabel.setText(longValue(param));
        refreshParamValues();
        controller.refresh();
    }

    private void refreshParamValues() {
        for (int i = 0; i < paramValues.length; i++) {
            paramValues[i].setText(shortValue(i));
            if (i != param) paramValues[i].setTextColor(isManual(i) ? 0xFFFFFFFF : 0xFFAEAEB2);
        }
        boolean manual = isManual(param);
        autoButton.setBackground(rounded(manual ? 0xFF3A3A3C : PANEL, 14));
        autoButton.setAlpha(manual ? 1f : 0.45f);
    }

    /** Stellt den Regler auf den aktuellen Wert der gewaehlten Groesse. */
    private void syncSeek() {
        if (param == P_NONE) return;
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
        sliderLabel.setText(longValue(param));
        refreshParamValues();
    }

    // ------------------------------------------------------------------- Fokus

    /** Antippen: dorthin fokussieren und dort belichten. Doppeltippen: zurueck zum Autofokus. */
    private void onPreviewTap(float x, float y, int width, int height) {
        CameraController.Lens lens = controller.currentLens();
        if (lens == null || width <= 0 || height <= 0) return;
        if (lookMenu.getVisibility() == View.VISIBLE || settingsMenu.getVisibility() == View.VISIBLE) return;
        float u = x / width;
        float v = y / height;
        float sx;
        float sy;
        switch (lens.sensorOrientation) {
            case 270:
                sx = 1f - v;
                sy = u;
                break;
            case 180:
                sx = 1f - u;
                sy = 1f - v;
                break;
            case 0:
                sx = u;
                sy = v;
                break;
            default:
                sx = v;
                sy = 1f - u;
                break;
        }
        controller.manualFocus = null;
        focusRing.show(x, y, ACCENT);
        controller.focusAt(sx, sy);
        refreshAll();
        syncSeek();
    }

    private void resetFocusPoint() {
        focusRing.hide();
        controller.clearFocus();
        refreshAll();
        syncSeek();
    }

    @Override
    public void onFocus(int state) {
        switch (state) {
            case -1:
                focusRing.hide();
                refreshAll();
                break;
            case 1:
                focusRing.setColor(0xFF4CD964);
                break;
            case 2:
                focusRing.setColor(0xFFFF3B30);
                break;
            default:
                focusRing.setColor(ACCENT);
                break;
        }
    }

    // ------------------------------------------------------------------ Aufnahme

    private void onShutter() {
        if (busy) return;
        // Lautlos: nur ein kurzes Abdunkeln der Vorschau als Rueckmeldung.
        flash.animate().cancel();
        flash.setAlpha(0.7f);
        flash.animate().alpha(0f).setDuration(180).start();
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

    // -------------------------------------------------------------------- Helfer

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView overlayText(int sp) {
        TextView t = text("", sp, 0xFFFFFFFF);
        t.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
