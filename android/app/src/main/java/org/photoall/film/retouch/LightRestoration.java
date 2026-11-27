package org.photoall.film.retouch;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

/** Real pretrained low-light networks. The caller owns original-size tiling and blending. */
final class LightRestoration implements AutoCloseable {
    static final int TILE = 256;
    private static final int PLANE = TILE * TILE;
    private final Context context;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private OrtSession iat, retinexformer;
    private boolean closed;

    LightRestoration(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Accepts one 256x256 ARGB tile in sRGB. Returns the selected model's RGB,
     * clamped and rounded to 8-bit while preserving input alpha. No strength
     * blend, image resize, service call, or automatic model substitution occurs.
     */
    synchronized int[] restore(RetouchRecipe.Light model, int[] rgb, int width, int height)
            throws IOException, OrtException, InterruptedException {
        if (closed) throw new IOException("补光模型已关闭");
        if (model != RetouchRecipe.Light.IAT && model != RetouchRecipe.Light.RETINEXFORMER) {
            throw new IllegalArgumentException("请选择 IAT 或 Retinexformer 补光模型");
        }
        if (width != TILE || height != TILE || rgb.length != PLANE) {
            throw new IllegalArgumentException("补光模型需要 256×256 像素块");
        }
        checkCancelled();
        float[] pixels = new float[3 * PLANE];
        for (int index = 0; index < PLANE; index++) {
            int color = rgb[index];
            pixels[index] = (color >>> 16 & 255) / 255f;
            pixels[PLANE + index] = (color >>> 8 & 255) / 255f;
            pixels[2 * PLANE + index] = (color & 255) / 255f;
        }
        OrtSession session = session(model);
        checkCancelled();
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels),
                    new long[]{1, 3, TILE, TILE});
             OrtSession.Result outputs = session.run(Collections.singletonMap("image", input))) {
            if (!outputs.get("restored").isPresent()
                    || !(outputs.get("restored").get() instanceof OnnxTensor)) {
                throw new IOException("补光模型缺少 RGB 输出，请重新安装完整 APK");
            }
            FloatBuffer restored = ((OnnxTensor) outputs.get("restored").get()).getFloatBuffer();
            if (restored.remaining() != PLANE * 3) throw new IOException("补光模型输出尺寸无效");
            int[] result = new int[PLANE];
            for (int index = 0; index < PLANE; index++) {
                if (index % TILE == 0) checkCancelled();
                int color = rgb[index] & 0xff000000;
                for (int channel = 0; channel < 3; channel++) {
                    float value = restored.get(channel * PLANE + index);
                    if (!Float.isFinite(value)) throw new IOException("补光模型返回非有限像素值");
                    color |= Math.round(Math.max(0, Math.min(1, value)) * 255) << (16 - 8 * channel);
                }
                result[index] = color;
            }
            return result;
        }
    }

    private OrtSession session(RetouchRecipe.Light model) throws IOException, OrtException {
        if (model == RetouchRecipe.Light.IAT) {
            if (iat == null) iat = open("offline/iat.onnx");
            return iat;
        }
        if (retinexformer == null) retinexformer = open("offline/retinexformer.onnx");
        return retinexformer;
    }

    private OrtSession open(String asset) throws IOException, OrtException {
        byte[] graph;
        try (InputStream stream = context.getAssets().open(asset)) {
            graph = Images.bytes(stream);
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            return environment.createSession(graph, options);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("补光处理已取消");
    }

    @Override public synchronized void close() throws OrtException {
        if (closed) return;
        closed = true;
        OrtException failure = null;
        if (iat != null) {
            try { iat.close(); } catch (OrtException error) { failure = error; }
            iat = null;
        }
        if (retinexformer != null) {
            try { retinexformer.close(); } catch (OrtException error) { if (failure == null) failure = error; }
            retinexformer = null;
        }
        if (failure != null) throw failure;
    }
}
