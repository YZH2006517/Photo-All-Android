package org.photoall.film.retouch;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

/** Official HDRNetCurves FiveK Expert C; learned guide and spatial bilateral slicing. */
final class HdrNetColor implements AutoCloseable {
    private static final int INPUT = 256, GRID = 16, DEPTH = 8, COEFFICIENTS = 12;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final float[] guide;

    HdrNetColor(Context context) throws Exception {
        byte[] network;
        try (InputStream input = context.getAssets().open("offline/hdrnet_fivek_c.onnx")) {
            network = Images.bytes(input);
        }
        try (InputStream input = context.getAssets().open("offline/hdrnet_guide.bin")) {
            byte[] bytes = Images.bytes(input);
            if (bytes.length != 8 + 112 * 4) throw new IOException("HDRNet 导向参数资源损坏");
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getInt() != 0x48445231 || buffer.getInt() != 112)
                throw new IOException("HDRNet 导向参数版本不匹配");
            guide = new float[112];
            buffer.asFloatBuffer().get(guide);
            finite(guide);
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            session = environment.createSession(network, options);
        }
    }

    /** Low-resolution image describes the full frame, stretched to the trained 256-square input. */
    Adjustment prepare(int[] argb, int width, int height, float strength) throws Exception {
        if (width <= 0 || height <= 0 || (long) width * height != argb.length)
            throw new IllegalArgumentException("HDRNet 输入尺寸无效");
        if (!Float.isFinite(strength) || strength < 0 || strength > 1)
            throw new IllegalArgumentException("HDRNet 强度必须在 0 到 1 之间");
        interrupted();
        int plane = INPUT * INPUT;
        float[] image = new float[3 * plane];
        // Original run.py uses skimage order=0. Match centered nearest-neighbor resizing.
        for (int y = 0; y < INPUT; y++) {
            int sy = Math.min(height - 1, (int) ((y + .5f) * height / INPUT));
            for (int x = 0; x < INPUT; x++) {
                int sx = Math.min(width - 1, (int) ((x + .5f) * width / INPUT));
                int pixel = argb[sy * width + sx], index = y * INPUT + x;
                image[index] = (pixel >>> 16 & 255) / 255f;
                image[plane + index] = (pixel >>> 8 & 255) / 255f;
                image[2 * plane + index] = (pixel & 255) / 255f;
            }
        }
        float[] grid = new float[GRID * GRID * DEPTH * COEFFICIENTS];
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(image), new long[]{1, 3, INPUT, INPUT});
             OrtSession.Result result = session.run(Collections.singletonMap("image", input))) {
            OnnxTensor tensor = (OnnxTensor) result.get(0);
            long[] shape = tensor.getInfo().getShape();
            if (shape.length != 6 || shape[0] != 1 || shape[1] != GRID || shape[2] != GRID ||
                    shape[3] != DEPTH || shape[4] != 3 || shape[5] != 4)
                throw new IOException("HDRNet 网格输出尺寸不匹配");
            FloatBuffer values = tensor.getFloatBuffer();
            if (values.remaining() != grid.length) throw new IOException("HDRNet 网格输出损坏");
            values.get(grid);
        }
        finite(grid);
        interrupted();
        return new Adjustment(grid, guide, strength);
    }

    static final class Adjustment {
        private final float[] grid, guide;
        private final float strength;

        private Adjustment(float[] grid, float[] guide, float strength) {
            this.grid = grid;
            this.guide = guide;
            this.strength = strength;
        }

        /** x,y are full-frame normalized pixel centers: (column+.5)/width, (row+.5)/height. */
        int apply(int pixel, float x, float y) {
            if (strength == 0 || (pixel >>> 24) == 0) return pixel;
            if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("HDRNet 像素坐标无效");
            float red = (pixel >>> 16 & 255) / 255f;
            float green = (pixel >>> 8 & 255) / 255f;
            float blue = (pixel & 255) / 255f;
            float gx = clamp(x) * GRID, gy = clamp(y) * GRID;
            float gz = guidance(red, green, blue) * DEPTH;
            int x0 = (int) Math.floor(gx - .5f), y0 = (int) Math.floor(gy - .5f);
            int z0 = (int) Math.floor(gz - .5f);
            int adjusted = pixel & 0xff000000;
            for (int output = 0; output < 3; output++) {
                float value = 0;
                for (int iy = y0; iy <= y0 + 1; iy++) {
                    int cy = bound(iy, GRID);
                    float wy = Math.max(0, 1 - Math.abs(iy + .5f - gy));
                    for (int ix = x0; ix <= x0 + 1; ix++) {
                        int cx = bound(ix, GRID);
                        float wxy = wy * Math.max(0, 1 - Math.abs(ix + .5f - gx));
                        for (int iz = z0; iz <= z0 + 1; iz++) {
                            int cz = bound(iz, DEPTH);
                            // Match official SmoothedLerpWeight, including its 1e-8 epsilon.
                            float delta = iz + .5f - gz;
                            float wz = Math.max(0, 1 - (float) Math.sqrt(delta * delta + 1e-8f));
                            int at = (((cy * GRID + cx) * DEPTH + cz) * 3 + output) * 4;
                            value += wxy * wz * (grid[at] * red + grid[at + 1] * green +
                                    grid[at + 2] * blue + grid[at + 3]);
                        }
                    }
                }
                float before = output == 0 ? red : output == 1 ? green : blue;
                adjusted |= Math.round(clamp(before + strength * (clamp(value) - before)) * 255f) << ((2 - output) * 8);
            }
            return adjusted;
        }

        private float guidance(float red, float green, float blue) {
            float mixed = guide[111];
            for (int channel = 0; channel < 3; channel++) {
                float transformed = red * guide[channel] + green * guide[3 + channel] +
                        blue * guide[6 + channel] + guide[9 + channel];
                float curve = 0;
                int shift = 12 + channel * 16, slope = 60 + channel * 16;
                for (int point = 0; point < 16; point++)
                    curve += guide[slope + point] * Math.max(0, transformed - guide[shift + point]);
                mixed += guide[108 + channel] * curve;
            }
            return clamp(mixed);
        }

        private static int bound(int value, int size) { return Math.max(0, Math.min(size - 1, value)); }
        private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    }

    private static void finite(float[] values) throws IOException {
        for (float value : values) if (!Float.isFinite(value)) throw new IOException("HDRNet 参数含无效数值");
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("调色已暂停");
    }

    @Override public void close() throws Exception { session.close(); }
}
