package org.photoall.film.retouch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** A small, serializable color profile from a user-picked reference. */
final class ReferenceLook {
    private static final int BINS = 24, CUBE_EDGE = 33;
    private static final int ENCODED_BYTES = 9 * 4;
    private final float[] stats;
    private final int[] palette;
    private final String imageKey;
    final RetouchRecipe.Match presetModel;

    private ReferenceLook(float[] stats, int[] palette) {
        this(stats, palette, null, RetouchRecipe.Match.CLASSIC);
    }

    private ReferenceLook(float[] stats, int[] palette, String imageKey, RetouchRecipe.Match presetModel) {
        this.stats = stats;
        this.palette = palette;
        this.imageKey = imageKey;
        this.presetModel = presetModel;
    }

    static ReferenceLook extract(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return measure(pixels);
    }

    static ReferenceLook extract(Context context, Bitmap bitmap) throws IOException {
        ReferenceLook look = extract(bitmap);
        if (look == null) return null;
        ByteArrayOutputStream thumbnail = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, thumbnail)) throw new IOException("无法保存参考图");
        byte[] bytes = thumbnail.toByteArray();
        String key;
        try {
            key = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        File destination = imageFile(context, key);
        if (!destination.isFile()) {
            File directory = destination.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("无法创建参考图目录");
            }
            File staging = File.createTempFile("reference-", ".part", directory);
            try {
                Files.write(staging.toPath(), bytes);
                if (!staging.renameTo(destination) && !destination.isFile()) throw new IOException("无法保存参考图");
            } finally {
                staging.delete();
            }
        }
        return new ReferenceLook(look.stats, look.palette, key, RetouchRecipe.Match.CLASSIC);
    }

    private static File imageFile(Context context, String key) {
        return new File(new File(context.getFilesDir(), "reference-looks"), key + ".png");
    }

    boolean hasImage() { return imageKey != null; }

    Bitmap image(Context context) throws IOException {
        Bitmap bitmap = imageKey == null ? null : BitmapFactory.decodeFile(imageFile(context, imageKey).getPath());
        if (bitmap == null) throw new IOException("请重新选择参考图");
        return bitmap;
    }

    ReferenceLook forPreset(RetouchRecipe.Match choice) {
        return new ReferenceLook(stats, palette, imageKey, hasImage() ? choice : RetouchRecipe.Match.CLASSIC);
    }

    int swatch(int index) { return palette[index]; }

    String encode() {
        ByteBuffer bytes = ByteBuffer.allocate(ENCODED_BYTES);
        for (float value : stats) bytes.putFloat(value);
        for (int color : palette) bytes.putInt(color);
        String values = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
        return imageKey == null ? values : values + "." + imageKey + "." + presetModel.name();
    }

    static ReferenceLook decode(String value) {
        if (value == null) return null;
        String[] parts = value.split("\\.", -1);
        if ((parts.length != 1 && parts.length != 3) || parts[0].length() != 48) return null;
        if (parts.length == 3 && !parts[1].matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(parts[0]);
            if (bytes.length != ENCODED_BYTES) return null;
            ByteBuffer data = ByteBuffer.wrap(bytes);
            float[] stats = new float[6];
            for (int i = 0; i < stats.length; i++) {
                float x = data.getFloat();
                if (!Float.isFinite(x) || x < (i == 1 || i == 2 ? -1 : 0) || x > 1) return null;
                stats[i] = x;
            }
            return new ReferenceLook(stats, new int[]{data.getInt(), data.getInt(), data.getInt()},
                    parts.length == 3 ? parts[1] : null,
                    parts.length == 3 ? RetouchRecipe.Match.valueOf(parts[2]) : RetouchRecipe.Match.CLASSIC);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static ReferenceLook measure(int[] pixels) {
        double[] sum = new double[3], squares = new double[3], lab = new double[3];
        int[] counts = new int[BINS], reds = new int[BINS], greens = new int[BINS], blues = new int[BINS];
        int count = 0;
        for (int pixel : pixels) {
            if ((pixel >>> 24) < 64) continue;
            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            toLab(r / 255.0, g / 255.0, b / 255.0, lab);
            for (int i = 0; i < 3; i++) {
                sum[i] += lab[i];
                squares[i] += lab[i] * lab[i];
            }
            int bin = Math.max(0, Math.min(BINS - 1, (int) (lab[0] * BINS)));
            counts[bin]++;
            reds[bin] += r;
            greens[bin] += g;
            blues[bin] += b;
            count++;
        }
        if (count == 0) return null;
        float[] stats = new float[6];
        for (int i = 0; i < 3; i++) {
            double mean = sum[i] / count;
            stats[i] = (float) mean;
            stats[i + 3] = (float) Math.sqrt(Math.max(0, squares[i] / count - mean * mean));
        }
        int[] palette = new int[3];
        for (int i = 0; i < 3; i++) {
            int rank = count * (2 + 3 * i) / 10;
            int through = 0;
            for (int bin = 0; bin < BINS; bin++) {
                through += counts[bin];
                if (through <= rank) continue;
                int n = counts[bin];
                palette[i] = 0xff000000 | (reds[bin] / n << 16) | (greens[bin] / n << 8) | (blues[bin] / n);
                break;
            }
        }
        return new ReferenceLook(stats, palette);
    }

    RgbLut fit(int[] sample) {
        ReferenceLook input = measure(sample);
        if (input == null) return null;
        double[] gain = new double[3], shift = new double[3];
        for (int k = 0; k < 3; k++) {
            double floor = k == 0 ? .04 : .012;
            gain[k] = range(Math.max(floor, stats[k + 3]) / Math.max(floor, input.stats[k + 3]), .45, 2.0);
            shift[k] = stats[k] - input.stats[k] * gain[k];
        }
        int size = CUBE_EDGE, volume = size * size * size;
        float[] cube = new float[3 * volume];
        double[] lab = new double[3], rgb = new double[3];
        int index = 0;
        for (int b = 0; b < size; b++) {
            for (int g = 0; g < size; g++) {
                for (int r = 0; r < size; r++, index++) {
                    toLab(r / (size - 1.0), g / (size - 1.0), b / (size - 1.0), lab);
                    double l = range(lab[0] * gain[0] + shift[0], 0, 1);
                    double a = lab[1] * gain[1] + shift[1], c = lab[2] * gain[2] + shift[2];
                    gamut(l, a, c, rgb);
                    for (int k = 0; k < 3; k++) cube[k * volume + index] = (float) srgb(range(rgb[k], 0, 1));
                }
            }
        }
        return new RgbLut(size, cube, null);
    }

    private static void toLab(double r, double g, double b, double[] lab) {
        r = linear(r);
        g = linear(g);
        b = linear(b);
        double l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b);
        double m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b);
        double s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
        lab[0] = .2104542553 * l + .7936177850 * m - .0040720468 * s;
        lab[1] = 1.9779984951 * l - 2.4285922050 * m + .4505937099 * s;
        lab[2] = .0259040371 * l + .7827717662 * m - .8086757660 * s;
    }

    private static void gamut(double l, double a, double b, double[] rgb) {
        fromLab(l, a, b, rgb);
        if (inGamut(rgb)) return;
        double low = 0, high = 1;
        for (int i = 0; i < 9; i++) {
            double mid = (low + high) / 2;
            fromLab(l, a * mid, b * mid, rgb);
            if (inGamut(rgb)) low = mid;
            else high = mid;
        }
        fromLab(l, a * low, b * low, rgb);
    }

    private static boolean inGamut(double[] rgb) {
        for (double v : rgb) if (v < -1e-6 || v > 1.000001) return false;
        return true;
    }

    private static void fromLab(double l, double a, double b, double[] rgb) {
        double x = l + .3963377774 * a + .2158037573 * b;
        double y = l - .1055613458 * a - .0638541728 * b;
        double z = l - .0894841775 * a - 1.2914855480 * b;
        x *= x * x;
        y *= y * y;
        z *= z * z;
        rgb[0] = 4.0767416621 * x - 3.3077115913 * y + .2309699292 * z;
        rgb[1] = -1.2684380046 * x + 2.6097574011 * y - .3413193965 * z;
        rgb[2] = -.0041960863 * x - .7034186147 * y + 1.7076147010 * z;
    }

    private static double linear(double value) {
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    private static double srgb(double value) {
        return value <= .0031308 ? 12.92 * value : 1.055 * Math.pow(value, 1 / 2.4) - .055;
    }

    private static double range(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
