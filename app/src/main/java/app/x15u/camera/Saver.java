package app.x15u.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/** Speichert DNG und JPEG nach DCIM/X15U. */
final class Saver {

    private static final String FOLDER = Environment.DIRECTORY_DCIM + "/X15U";

    private static final String[] EXIF_TAGS = {
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE,
    };

    private Saver() {
    }

    private static Uri insert(Context context, String name, String mime) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Datei konnte nicht angelegt werden");
        return uri;
    }

    private static void write(Context context, Uri uri, byte[] data) throws Exception {
        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("Datei nicht beschreibbar");
            os.write(data);
        }
    }

    /**
     * Schreibt das Sensorbild unveraendert als DNG. Versucht zuerst die Aufnahmedaten des
     * einzelnen Objektivs, sonst die der Gesamtkamera.
     */
    static void saveDng(Context context, String name, Image image, CameraCharacteristics characteristics,
                        TotalCaptureResult preferred, TotalCaptureResult fallback, int exifOrientation)
            throws Exception {
        byte[] data = null;
        Exception last = null;
        TotalCaptureResult[] candidates = {preferred, fallback};
        for (TotalCaptureResult result : candidates) {
            if (result == null || data != null) continue;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(image.getWidth() * image.getHeight() * 2 + 65536);
                DngCreator creator = new DngCreator(characteristics, result);
                try {
                    creator.setOrientation(exifOrientation);
                    creator.setDescription("X15U Kamera, unverarbeitet");
                    creator.writeImage(out, image);
                } finally {
                    creator.close();
                }
                data = out.toByteArray();
            } catch (Exception e) {
                last = e;
            }
        }
        if (data == null) throw last != null ? last : new IllegalStateException("keine Aufnahmedaten");
        write(context, insert(context, name, "image/x-adobe-dng"), data);
    }

    /** Wendet den Look auf das Kamera-JPEG an und speichert das Ergebnis mit den Aufnahmedaten. */
    /**
     * Schneidet mittig auf das Seitenverhaeltnis zu (lange zu kurze Seite). Funktioniert
     * unabhaengig davon, ob das Bild hoch oder quer gespeichert ist.
     */
    static Bitmap crop(Bitmap bitmap, float ratio) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        boolean landscape = w >= h;
        int longSide = landscape ? w : h;
        int shortSide = landscape ? h : w;
        float current = longSide / (float) shortSide;
        if (Math.abs(ratio - current) < 0.01f) return bitmap;
        if (ratio > current) {
            shortSide = Math.round(longSide / ratio);
        } else {
            longSide = Math.round(shortSide * ratio);
        }
        int nw = landscape ? longSide : shortSide;
        int nh = landscape ? shortSide : longSide;
        Bitmap cropped = Bitmap.createBitmap(bitmap, (w - nw) / 2, (h - nh) / 2, nw, nh);
        if (!cropped.isMutable()) {
            Bitmap copy = cropped.copy(Bitmap.Config.ARGB_8888, true);
            if (cropped != bitmap) cropped.recycle();
            cropped = copy;
        }
        if (cropped != bitmap) bitmap.recycle();
        return cropped;
    }

    static void saveJpeg(Context context, String name, byte[] source, Look look, float aspect) throws Exception {
        boolean fullFrame = Math.abs(aspect - 4f / 3f) < 0.01f;
        if ((look == null || look.identity) && fullFrame) {
            write(context, insert(context, name, "image/jpeg"), source);
            return;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (bitmap == null) throw new IllegalStateException("Kamerabild nicht lesbar");
        bitmap = crop(bitmap, aspect);
        Uri uri;
        try {
            LookEngine.apply(bitmap, look);
            uri = insert(context, name, "image/jpeg");
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("Datei nicht beschreibbar");
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, os);
            }
        } finally {
            bitmap.recycle();
        }
        copyExif(context, source, uri, look == null ? new Look(Look.NATURAL, "Standard") : look);
    }

    private static void copyExif(Context context, byte[] source, Uri target, Look look) {
        ContentResolver resolver = context.getContentResolver();
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(target, "rw")) {
            if (pfd == null) return;
            ExifInterface in = new ExifInterface(new ByteArrayInputStream(source));
            ExifInterface out = new ExifInterface(pfd.getFileDescriptor());
            for (String tag : EXIF_TAGS) {
                String value = in.getAttribute(tag);
                if (value != null) out.setAttribute(tag, value);
            }
            out.setAttribute(ExifInterface.TAG_SOFTWARE, "X15U Kamera, Look " + look.name);
            out.saveAttributes();
        } catch (Throwable ignored) {
            // Das Bild ist gespeichert; fehlende Aufnahmedaten sind kein Grund fuer einen Abbruch.
        }
    }
}
