package org.photoall.film.retouch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** The image helpers the retouch engine needs (from ZRetouch's Images, without its library and RAW import). */
final class Images {
    private Images() { }

    private static final long MAX = 40L * 1024 * 1024;

    /** Photo All hands the engine decoded pictures only; RAW files go through the film engine. */
    static boolean isRaw(String name) { return false; }

    static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + (long) count > MAX) throw new IOException("图片超过 40 MB");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    static Bitmap oriented(String path, int edge) throws IOException {
        BitmapFactory.Options options = bounds(path);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("无法解码原片");
        sampleSize(options, edge);
        options.inMutable = true;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(path, options);
        if (decoded == null) throw new IOException("无法解码原片");
        return oriented(decoded, orientation(path), edge);
    }

    private static Bitmap oriented(Bitmap decoded, int orientation, int edge) throws IOException {
        float scale = Math.min(1f, edge / (float) Math.max(decoded.getWidth(), decoded.getHeight()));
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2 -> matrix.setScale(-1, 1);
            case 3 -> matrix.setRotate(180);
            case 4 -> matrix.setScale(1, -1);
            case 5 -> { matrix.setRotate(90); matrix.postScale(-1, 1); }
            case 6 -> matrix.setRotate(90);
            case 7 -> { matrix.setRotate(-90); matrix.postScale(-1, 1); }
            case 8 -> matrix.setRotate(-90);
            default -> { }
        }
        matrix.postScale(scale, scale);
        Bitmap result = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (result != decoded) decoded.recycle();
        if (!result.isMutable()) {
            Bitmap mutable = result.copy(Bitmap.Config.ARGB_8888, true);
            result.recycle();
            result = mutable;
        }
        if (result == null) throw new IOException("手机内存不足，无法解码原片");
        return result;
    }

    static int orientation(String path) {
        try { return orientation(new ExifInterface(path)); }
        catch (IOException ignored) { return 1; }
    }

    private static int orientation(ExifInterface exif) {
        int value = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        return value >= 1 && value <= 8 ? value : 1;
    }

    private static BitmapFactory.Options bounds(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        return options;
    }

    private static void sampleSize(BitmapFactory.Options options, int edge) {
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > edge * 2) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
    }
}
