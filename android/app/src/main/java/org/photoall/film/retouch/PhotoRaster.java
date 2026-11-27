package org.photoall.film.retouch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ColorSpace;
import android.graphics.Rect;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;

/** Row access in display orientation, without a full-size Java bitmap. */
abstract class PhotoRaster implements AutoCloseable {
    final int width, height;

    PhotoRaster(int width, int height) {
        this.width = width;
        this.height = height;
    }

    abstract void read(int top, int count, int[] pixels) throws IOException;
    @Override public abstract void close() throws IOException;

    abstract int[] sample(int columns, int rows) throws IOException;

    static PhotoRaster open(String path) throws IOException {
        return new Original(path);
    }

    static final class BitmapSource extends PhotoRaster {
        private final Bitmap bitmap;

        BitmapSource(Bitmap bitmap) {
            super(bitmap.getWidth(), bitmap.getHeight());
            this.bitmap = bitmap;
        }

        @Override void read(int top, int count, int[] pixels) {
            bitmap.getPixels(pixels, 0, width, 0, top, width, count);
        }

        @Override int[] sample(int columns, int rows) {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, columns, rows, true);
            try {
                int[] pixels = new int[columns * rows];
                small.getPixels(pixels, 0, columns, 0, 0, columns, rows);
                return pixels;
            } finally {
                if (small != bitmap) small.recycle();
            }
        }

        @Override public void close() { }
    }

    static final class Original extends PhotoRaster {
        private final BitmapRegionDecoder decoder;
        private final int orientation, rawWidth, rawHeight;
        private final String path;
        private final BitmapFactory.Options options = new BitmapFactory.Options();
        private int[] decoded = new int[0];

        Original(String path) throws IOException {
            this(path, Images.orientation(path), openRegion(path));
        }

        private Original(String path, int orientation, BitmapRegionDecoder decoder) {
            super(orientation >= 5 ? decoder.getHeight() : decoder.getWidth(),
                    orientation >= 5 ? decoder.getWidth() : decoder.getHeight());
            this.path = path;
            this.decoder = decoder;
            this.orientation = orientation;
            rawWidth = decoder.getWidth();
            rawHeight = decoder.getHeight();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            options.inPremultiplied = false;
        }

        @SuppressWarnings("deprecation")
        private static BitmapRegionDecoder openRegion(String path) throws IOException {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(path, false);
            if (decoder == null) throw new IOException("无法读取原尺寸照片");
            return decoder;
        }

        @Override void read(int top, int count, int[] pixels) throws IOException {
            int bottom = top + count;
            Rect region = switch (orientation) {
                case 3, 4 -> new Rect(0, rawHeight - bottom, rawWidth, rawHeight - top);
                case 5, 6 -> new Rect(top, 0, bottom, rawHeight);
                case 7, 8 -> new Rect(rawWidth - bottom, 0, rawWidth - top, rawHeight);
                default -> new Rect(0, top, rawWidth, bottom);
            };
            Bitmap bitmap = decoder.decodeRegion(region, options);
            if (bitmap == null) throw new IOException("无法解码原尺寸像素");
            try {
                int stride = region.width();
                if (bitmap.getWidth() != stride || bitmap.getHeight() != region.height()) {
                    throw new IOException("原片解码尺寸异常");
                }
                if (orientation == 1) {
                    bitmap.getPixels(pixels, 0, stride, 0, 0, stride, count);
                    return;
                }
                int size = stride * region.height();
                if (decoded.length < size) decoded = new int[size];
                bitmap.getPixels(decoded, 0, stride, 0, 0, stride, region.height());
                int step = switch (orientation) {
                    case 2, 3 -> -1;
                    case 5, 8 -> stride;
                    case 6, 7 -> -stride;
                    default -> 1;
                };
                for (int row = 0; row < count; row++) {
                    int from = switch (orientation) {
                        case 2 -> row * stride + stride - 1;
                        case 3 -> (count - row) * stride - 1;
                        case 4 -> (count - row - 1) * stride;
                        case 5 -> row;
                        case 6 -> (rawHeight - 1) * stride + row;
                        case 7 -> rawHeight * stride - row - 1;
                        default -> stride - row - 1;
                    };
                    int to = row * width;
                    for (int x = 0; x < width; x++, from += step) pixels[to + x] = decoded[from];
                }
            } finally {
                bitmap.recycle();
            }
        }

        @Override int[] sample(int columns, int rows) throws IOException {
            Bitmap reduced = Images.oriented(path, Math.max(columns, rows) * 2);
            Bitmap scaled = reduced;
            try {
                scaled = Bitmap.createScaledBitmap(reduced, columns, rows, true);
                int[] pixels = new int[columns * rows];
                scaled.getPixels(pixels, 0, columns, 0, 0, columns, rows);
                return pixels;
            } finally {
                if (scaled != reduced) scaled.recycle();
                reduced.recycle();
            }
        }

        @Override public void close() { decoder.recycle(); }
    }

    /** Full-size intermediate pixels for the global analysis after denoising. */
    static final class Scratch extends PhotoRaster {
        private final File file;
        private final FileChannel channel;
        private final ByteBuffer buffer;
        private int written;

        Scratch(File directory, int width, int height) throws IOException {
            super(width, height);
            buffer = ByteBuffer.allocate(width * 4 * 8);
            file = File.createTempFile("retouch-", ".raster", directory);
            try {
                channel = new RandomAccessFile(file, "rw").getChannel();
            } catch (IOException failure) {
                file.delete();
                throw failure;
            }
        }

        void append(int[] pixels, int count) throws IOException {
            transfer(written, count, pixels, true);
            written += count;
        }

        @Override void read(int top, int count, int[] pixels) throws IOException {
            if (top < 0 || top + count > written) throw new IOException("中间图像不完整");
            transfer(top, count, pixels, false);
        }

        private void transfer(int top, int count, int[] pixels, boolean write) throws IOException {
            channel.position((long) top * width * 4);
            for (int offset = 0; offset < count; offset += 8) {
                int length = Math.min(count - offset, 8) * width;
                buffer.clear();
                buffer.limit(length * 4);
                IntBuffer ints = buffer.asIntBuffer();
                if (write) {
                    ints.put(pixels, offset * width, length);
                    while (buffer.hasRemaining()) channel.write(buffer);
                } else {
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) throw new IOException("中间图像不完整");
                    }
                    ints.get(pixels, offset * width, length);
                }
            }
        }

        @Override int[] sample(int columns, int rows) throws IOException {
            int[] pixels = new int[columns * rows];
            int[] upper = new int[width], lower = new int[width];
            int[] left = new int[columns];
            float[] horizontal = new float[columns];
            for (int x = 0; x < columns; x++) {
                float point = sampleCoordinate(x, columns, width);
                left[x] = (int) point;
                horizontal[x] = point - left[x];
            }
            for (int y = 0; y < rows; y++) {
                float point = sampleCoordinate(y, rows, height);
                int top = (int) point, bottom = Math.min(height - 1, top + 1);
                float vertical = point - top;
                read(top, 1, upper);
                read(bottom, 1, lower);
                for (int x = 0; x < columns; x++) {
                    int a = left[x], b = Math.min(width - 1, a + 1);
                    float u = horizontal[x];
                    int pixel = 0;
                    for (int shift = 0; shift <= 24; shift += 8) {
                        float first = (upper[a] >>> shift & 255) * (1 - u) + (upper[b] >>> shift & 255) * u;
                        float second = (lower[a] >>> shift & 255) * (1 - u) + (lower[b] >>> shift & 255) * u;
                        pixel |= Math.round(first * (1 - vertical) + second * vertical) << shift;
                    }
                    pixels[y * columns + x] = pixel;
                }
            }
            return pixels;
        }

        private static float sampleCoordinate(int index, int target, int source) {
            return Math.max(0, Math.min(source - 1, (index + .5f) * source / target - .5f));
        }

        @Override public void close() throws IOException {
            try { channel.close(); } finally { file.delete(); }
        }
    }
}
