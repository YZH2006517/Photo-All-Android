package org.photoall.film.retouch;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;

/** Official NAFNet SIDD width32 weights. Caller tiles with context; no synthetic fallback. */
final class NAFDenoiser implements AutoCloseable {
    static final int TILE = 256;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;

    NAFDenoiser(Context context) throws Exception {
        interrupted();
        // The APK stores the model uncompressed, so loading it needs no Java heap copy.
        try (AssetFileDescriptor descriptor = context.getAssets().openFd("offline/nafnet_sidd_w32.onnx");
             FileInputStream input = descriptor.createInputStream();
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            session = environment.createSession(input.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, descriptor.getStartOffset(), descriptor.getLength()), options);
        }
    }

    /** Input/output sRGB ARGB. Exactly TILE square; preserve alpha, clamp only network output. */
    int[] apply(int[] argb, int width, int height) throws Exception {
        if (width != TILE || height != TILE || argb.length != width * height)
            throw new IllegalArgumentException("NAFNet 输入必须是 " + TILE + "×" + TILE);
        interrupted();
        int plane = width * height;
        float[] values = new float[plane * 3];
        for (int i = 0; i < plane; i++) {
            values[i] = (argb[i] >>> 16 & 255) / 255f;
            values[plane + i] = (argb[i] >>> 8 & 255) / 255f;
            values[2 * plane + i] = (argb[i] & 255) / 255f;
        }
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{1, 3, height, width});
             OrtSession.Result result = session.run(Collections.singletonMap("input", input))) {
            FloatBuffer output = ((OnnxTensor) result.get(0)).getFloatBuffer();
            if (output.remaining() != values.length) throw new IOException("NAFNet 输出尺寸无效");
            output.get(values);
        }
        interrupted();
        int[] restored = new int[plane];
        for (int i = 0; i < plane; i++) {
            int pixel = argb[i] & 0xff000000;
            for (int c = 0; c < 3; c++) {
                float value = values[c * plane + i];
                if (!Float.isFinite(value)) throw new IOException("NAFNet 输出含无效数值");
                pixel |= Math.round(Math.max(0f, Math.min(1f, value)) * 255f) << ((2 - c) * 8);
            }
            restored[i] = pixel;
        }
        return restored;
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("降噪已暂停");
    }

    @Override public void close() throws Exception { session.close(); }
}
