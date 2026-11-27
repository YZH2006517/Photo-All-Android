package org.photoall.film.retouch;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Arbitrary reference-photo stylization using the packaged PhotoWCT and WCT2 checkpoints. */
final class PhotoStyleTransfer {
    enum Model { FAST_PHOTO_STYLE, WCT2 }

    static final int REFERENCE_EDGE = 256;
    private static final int MODEL_EDGE = 192, MAX_COVARIANCE_SAMPLES = 1024;
    private static final double EIGEN_CUTOFF = 1e-5;
    private static final String[] WAVE_DIRECTIONS = {"lh", "hl", "hh"};
    private static final ReentrantLock RENDER_LOCK = new ReentrantLock();
    private static Cached last;

    private final Context context;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();

    private PhotoStyleTransfer(Context context) {
        this.context = context;
    }

    static int[] render(Context context, Model model, int[] target, int targetWidth, int targetHeight,
                        int[] reference, int referenceWidth, int referenceHeight) throws IOException {
        if (model == null) throw new IOException("请选择离线风格迁移模型");
        validate(target, targetWidth, targetHeight);
        validate(reference, referenceWidth, referenceHeight);
        try {
            RENDER_LOCK.lockInterruptibly();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("风格迁移已取消");
        }
        try {
            if (last != null && last.matches(model, target, targetWidth, targetHeight,
                    reference, referenceWidth, referenceHeight)) return last.result.clone();
            PhotoStyleTransfer engine = new PhotoStyleTransfer(context);
            Sample content = sample(target, targetWidth, targetHeight);
            Sample style = sample(reference, referenceWidth, referenceHeight);
            try (OrtSession matrix = engine.session("matmul.onnx")) {
                Feature transformed = model == Model.FAST_PHOTO_STYLE
                        ? engine.fastPhotoStyle(content.tensor, style.tensor, matrix)
                        : engine.waveletStyle(content.tensor, style.tensor, matrix);
                if (transformed.channels != 3 || transformed.width != content.tensor.width
                        || transformed.height != content.tensor.height) throw new IOException("风格迁移输出尺寸无效");
                float[][] original = content.croppedChannels(content.tensor);
                float[][] styled = content.croppedChannels(transformed);
                if (model == Model.FAST_PHOTO_STYLE) guidedSmooth(original, styled, content.width, content.height);
                int[] result = reconstruct(target, targetWidth, targetHeight, content, original, styled);
                if (target.length <= REFERENCE_EDGE * REFERENCE_EDGE && reference.length <= REFERENCE_EDGE * REFERENCE_EDGE) {
                    last = new Cached(model, target.clone(), targetWidth, targetHeight, reference.clone(),
                            referenceWidth, referenceHeight, result.clone());
                }
                return result;
            } catch (OrtException failure) {
                throw new IOException("无法运行离线风格迁移", failure);
            }
        } finally {
            RENDER_LOCK.unlock();
        }
    }

    private Feature fastPhotoStyle(Feature content, Feature reference, OrtSession matrix) throws IOException, OrtException {
        Coloring[] referenceColors = new Coloring[4];
        for (int level = 4; level >= 1; level--) {
            checkCancelled();
            String featureName = "feature" + level;
            Set<String> outputs = new LinkedHashSet<>();
            outputs.add(featureName);
            for (int stage = 1; stage < level; stage++) outputs.add("pool" + stage);
            try (OrtSession encoder = session("fast_encoder" + level + ".onnx")) {
                if (level == 4) {
                    try (OnnxTensor image = tensor(reference);
                         OrtSession.Result analysis = encoder.run(Collections.singletonMap("image", image),
                                 new LinkedHashSet<>(Arrays.asList("feature1", "feature2", "feature3", "feature4")))) {
                        for (int stage = 1; stage <= 4; stage++) {
                            Feature style = outputFeature(analysis, "feature" + stage);
                            referenceColors[stage - 1] = coloring(matrix, style, false);
                        }
                    }
                }
                try (OnnxTensor image = tensor(content);
                     OrtSession.Result encoded = encoder.run(Collections.singletonMap("image", image), outputs);
                     OrtSession decoder = session("fast_decoder" + level + ".onnx")) {
                    Feature feature = outputFeature(encoded, featureName);
                    transfer(matrix, feature, referenceColors[level - 1], false);
                    Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
                    for (int stage = 1; stage < level; stage++) {
                        String key = "pool" + stage;
                        inputs.put(key, outputTensor(encoded, key));
                    }
                    try (OnnxTensor adjusted = tensor(feature)) {
                        inputs.put("feature", adjusted);
                        try (OrtSession.Result decoded = decoder.run(inputs)) {
                            content = outputFeature(decoded, "image");
                        }
                    }
                }
            }
        }
        return content;
    }

    private Feature waveletStyle(Feature content, Feature style, OrtSession matrix) throws IOException, OrtException {
        Map<String, Feature> skips = new LinkedHashMap<>();
        for (int level = 1; level <= 4; level++) {
            checkCancelled();
            try (OrtSession encoder = session("wave_encoder" + level + ".onnx");
                 OnnxTensor styleInput = tensor(style);
                 OrtSession.Result styleResult = encoder.run(Collections.singletonMap("input", styleInput),
                         Collections.singleton("feature"));
                 OnnxTensor contentInput = tensor(content);
                 OrtSession.Result contentResult = encoder.run(Collections.singletonMap("input", contentInput))) {
                style = outputFeature(styleResult, "feature");
                content = outputFeature(contentResult, "feature");
                // Style must continue through the reference encoder with its features intact.
                transfer(matrix, content, coloring(matrix, style.copy(), true), true);
                if (level < 4) {
                    for (String direction : WAVE_DIRECTIONS) {
                        String name = "wave" + level + "_" + direction;
                        skips.put(name, outputFeature(contentResult, name));
                    }
                }
            }
        }
        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try (OrtSession decoder = session("wave_decoder.onnx")) {
            inputs.put("feature", tensor(content));
            for (Map.Entry<String, Feature> band : skips.entrySet()) {
                inputs.put(band.getKey(), tensor(band.getValue()));
            }
            skips.clear();
            try (OrtSession.Result result = decoder.run(inputs)) {
                return outputFeature(result, "image");
            }
        } finally {
            for (OnnxTensor tensor : inputs.values()) tensor.close();
        }
    }

    private Coloring coloring(OrtSession matrix, Feature style, boolean identity) throws IOException, OrtException {
        float[] means = center(style);
        return new Coloring(means, covarianceRoot(matrix, style, false, identity));
    }

    private void transfer(OrtSession matrix, Feature content, Coloring style, boolean clamp) throws IOException, OrtException {
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        if (clamp) {
            for (float value : content.values) {
                lo = Math.min(lo, value);
                hi = Math.max(hi, value);
            }
        }
        center(content);
        int channels = content.channels, area = content.area();
        float[] whitening = covarianceRoot(matrix, content, true, true);
        float[] mapping = product(matrix, style.root, channels, channels, whitening, channels);
        float[] transformed = product(matrix, mapping, channels, channels, content.values, area);
        for (int channel = 0; channel < channels; channel++) {
            int offset = channel * area;
            for (int pixel = 0; pixel < area; pixel++) {
                float value = transformed[offset + pixel] + style.means[channel];
                content.values[offset + pixel] = clamp ? Math.max(lo, Math.min(hi, value)) : value;
            }
        }
    }

    private static float[] center(Feature feature) throws IOException {
        int area = feature.area();
        float[] means = new float[feature.channels];
        for (int channel = 0; channel < feature.channels; channel++) {
            checkCancelled();
            int offset = channel * area;
            double sum = 0;
            for (int i = 0; i < area; i++) sum += feature.values[offset + i];
            float mean = (float) (sum / area);
            if (!Float.isFinite(mean)) throw new IOException("风格迁移特征无效");
            means[channel] = mean;
            for (int i = 0; i < area; i++) feature.values[offset + i] -= mean;
        }
        return means;
    }

    private float[] covarianceRoot(OrtSession matrix, Feature feature, boolean inverse, boolean identity)
            throws IOException, OrtException {
        int area = feature.area(), channels = feature.channels;
        int count = Math.min(area, MAX_COVARIANCE_SAMPLES);
        float[] samples = new float[channels * count], transposed = new float[channels * count];
        float scale = (float) (1 / Math.sqrt(Math.max(1, count - 1)));
        // Sample one position per equal spatial interval with deterministic jitter to avoid lattice aliasing.
        int[] positions = new int[count];
        int random = 0x4193af23;
        for (int sample = 0; sample < count; sample++) {
            random = random * 1664525 + 1013904223;
            int start = sample * area / count, end = (sample + 1) * area / count;
            positions[sample] = start + (random >>> 1) % (end - start);
        }
        for (int channel = 0; channel < channels; channel++) {
            for (int sample = 0; sample < count; sample++) {
                float value = feature.values[channel * area + positions[sample]] * scale;
                samples[channel * count + sample] = value;
                transposed[sample * channels + channel] = value;
            }
        }
        float[] covariance = product(matrix, samples, channels, count, transposed, channels);
        double[][] symmetric = new double[channels][channels];
        for (int row = 0; row < channels; row++) {
            for (int col = 0; col <= row; col++) {
                double entry = .5 * ((double) covariance[row * channels + col] + covariance[col * channels + row]);
                if (identity && row == col) entry += 1;
                if (!Double.isFinite(entry)) throw new IOException("风格迁移协方差无效");
                symmetric[row][col] = symmetric[col][row] = entry;
            }
        }
        SymmetricEigen eigen = new SymmetricEigen(symmetric);
        float[] weighted = new float[channels * channels], basisTranspose = new float[channels * channels];
        for (int col = 0; col < channels; col++) {
            double value = eigen.d[col];
            if (value < EIGEN_CUTOFF) continue;
            float root = (float) Math.sqrt(value);
            if (inverse) root = 1 / root;
            for (int row = 0; row < channels; row++) {
                float vector = (float) eigen.V[row][col];
                weighted[row * channels + col] = vector * root;
                basisTranspose[col * channels + row] = vector;
            }
        }
        return product(matrix, weighted, channels, channels, basisTranspose, channels);
    }

    private float[] product(OrtSession session, float[] left, int rows, int inner, float[] right, int columns)
            throws IOException, OrtException {
        checkCancelled();
        try (OnnxTensor a = OnnxTensor.createTensor(environment, FloatBuffer.wrap(left), new long[]{rows, inner});
             OnnxTensor b = OnnxTensor.createTensor(environment, FloatBuffer.wrap(right), new long[]{inner, columns})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("left", a);
            inputs.put("right", b);
            try (OrtSession.Result result = session.run(inputs)) {
                return outputValues(outputTensor(result, "value"), rows * columns);
            }
        }
    }

    private OrtSession session(String name) throws IOException, OrtException {
        checkCancelled();
        try (InputStream in = context.getAssets().open("photo_style/" + name);
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            options.setMemoryPatternOptimization(false);
            return environment.createSession(Images.bytes(in), options);
        }
    }

    private OnnxTensor tensor(Feature feature) throws OrtException {
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(feature.values),
                new long[]{1, feature.channels, feature.height, feature.width});
    }

    private static OnnxTensor outputTensor(OrtSession.Result result, String name) throws IOException {
        if (result.get(name).orElse(null) instanceof OnnxTensor tensor) return tensor;
        throw new IOException("风格迁移模型缺少输出 " + name);
    }

    private static Feature outputFeature(OrtSession.Result result, String name) throws IOException {
        OnnxTensor tensor = outputTensor(result, name);
        long[] shape = tensor.getInfo().getShape();
        if (shape.length != 4 || shape[0] != 1 || shape[1] < 1 || shape[1] > 512
                || shape[2] < 1 || shape[2] > MODEL_EDGE || shape[3] < 1 || shape[3] > MODEL_EDGE) {
            throw new IOException("风格迁移模型输出形状无效");
        }
        int channels = (int) shape[1], height = (int) shape[2], width = (int) shape[3];
        return new Feature(outputValues(tensor, channels * height * width), channels, width, height);
    }

    private static float[] outputValues(OnnxTensor tensor, int length) throws IOException {
        FloatBuffer value = tensor.getFloatBuffer();
        if (value == null || value.remaining() != length) throw new IOException("风格迁移模型输出尺寸无效");
        float[] values = new float[length];
        value.get(values);
        return values;
    }

    private static Sample sample(int[] pixels, int width, int height) throws IOException {
        float scale = Math.min(1f, MODEL_EDGE / (float) Math.max(width, height));
        int sampledWidth = Math.max(1, Math.round(width * scale)), sampledHeight = Math.max(1, Math.round(height * scale));
        int paddedWidth = Math.max(16, (sampledWidth + 7) & ~7), paddedHeight = Math.max(16, (sampledHeight + 7) & ~7);
        int area = paddedWidth * paddedHeight;
        float[] values = new float[3 * area];
        for (int y = 0; y < paddedHeight; y++) {
            checkCancelled();
            float sy = position(Math.min(y, sampledHeight - 1), sampledHeight, height);
            int top = (int) sy, bottom = Math.min(top + 1, height - 1);
            for (int x = 0; x < paddedWidth; x++) {
                float sx = position(Math.min(x, sampledWidth - 1), sampledWidth, width);
                int left = (int) sx, right = Math.min(left + 1, width - 1);
                int a = pixels[top * width + left], b = pixels[top * width + right];
                int c = pixels[bottom * width + left], d = pixels[bottom * width + right];
                for (int channel = 0; channel < 3; channel++) {
                    int shift = (2 - channel) * 8;
                    float upper = mix(a >>> shift & 255, b >>> shift & 255, sx - left);
                    float lower = mix(c >>> shift & 255, d >>> shift & 255, sx - left);
                    values[channel * area + y * paddedWidth + x] = mix(upper, lower, sy - top) / 255f;
                }
            }
        }
        return new Sample(new Feature(values, 3, paddedWidth, paddedHeight), sampledWidth, sampledHeight);
    }

    private static int[] reconstruct(int[] pixels, int width, int height, Sample content,
                                     float[][] original, float[][] styled) throws IOException {
        int smallWidth = content.width, smallHeight = content.height;
        for (int channel = 0; channel < 3; channel++) {
            for (int i = 0; i < original[channel].length; i++) {
                if (!Float.isFinite(styled[channel][i])) throw new IOException("风格迁移颜色输出无效");
                styled[channel][i] = (bounded(styled[channel][i]) - original[channel][i]) * 255;
            }
        }
        int[] output = pixels.clone();
        float[] columns = new float[width];
        for (int x = 0; x < width; x++) columns[x] = position(x, width, smallWidth);
        for (int y = 0; y < height; y++) {
            checkCancelled();
            float sy = position(y, height, smallHeight);
            int top = (int) sy, bottom = Math.min(top + 1, smallHeight - 1);
            for (int x = 0; x < width; x++) {
                int offset = y * width + x, pixel = pixels[offset], adjusted = pixel & 0xff000000;
                if (adjusted == 0) continue;
                float sx = columns[x];
                int left = (int) sx, right = Math.min(left + 1, smallWidth - 1);
                for (int channel = 0; channel < 3; channel++) {
                    float[] residual = styled[channel];
                    float a = mix(residual[top * smallWidth + left], residual[top * smallWidth + right], sx - left);
                    float b = mix(residual[bottom * smallWidth + left], residual[bottom * smallWidth + right], sx - left);
                    int shift = (2 - channel) * 8;
                    int value = Math.round((pixel >>> shift & 255) + mix(a, b, sy - top));
                    adjusted |= Math.max(0, Math.min(255, value)) << shift;
                }
                output[offset] = adjusted;
            }
        }
        return output;
    }

    /** The fast PhotoWCT path uses a color-guided filter with the original photo as guidance. */
    private static void guidedSmooth(float[][] guide, float[][] styled, int width, int height) throws IOException {
        int area = width * height;
        int radius = Math.max(1, Math.round(Math.max(width, height) * (35f / MODEL_EDGE)));
        float[][] guideMean = new float[3][];
        for (int channel = 0; channel < 3; channel++) guideMean[channel] = box(guide[channel], width, height, radius);
        float[][] inverse = new float[6][];
        int covariance = 0;
        for (int first = 0; first < 3; first++) {
            for (int second = first; second < 3; second++) {
                inverse[covariance++] = localCovariance(guide[first], guide[second],
                        guideMean[first], guideMean[second], width, height, radius);
            }
        }
        for (int pixel = 0; pixel < area; pixel++) {
            final double epsilon = .001;
            double rr = inverse[0][pixel] + epsilon, rg = inverse[1][pixel], rb = inverse[2][pixel];
            double gg = inverse[3][pixel] + epsilon, gb = inverse[4][pixel], bb = inverse[5][pixel] + epsilon;
            double coR = gg * bb - gb * gb, coRg = rb * gb - rg * bb, coRb = rg * gb - rb * gg;
            double coG = rr * bb - rb * rb, coGb = rb * rg - rr * gb, coB = rr * gg - rg * rg;
            double determinant = rr * coR + rg * coRg + rb * coRb;
            inverse[0][pixel] = (float) (coR / determinant);
            inverse[1][pixel] = (float) (coRg / determinant);
            inverse[2][pixel] = (float) (coRb / determinant);
            inverse[3][pixel] = (float) (coG / determinant);
            inverse[4][pixel] = (float) (coGb / determinant);
            inverse[5][pixel] = (float) (coB / determinant);
        }
        for (int channel = 0; channel < 3; channel++) {
            checkCancelled();
            float[] output = styled[channel];
            for (int pixel = 0; pixel < area; pixel++) output[pixel] = bounded(output[pixel]);
            float[] mean = box(output, width, height, radius);
            float[][] covariances = new float[3][];
            for (int axis = 0; axis < 3; axis++) {
                covariances[axis] = localCovariance(guide[axis], output, guideMean[axis],
                        mean, width, height, radius);
            }
            float[] red = covariances[0], green = covariances[1], blue = covariances[2];
            for (int pixel = 0; pixel < area; pixel++) {
                float r = red[pixel], g = green[pixel], b = blue[pixel];
                red[pixel] = inverse[0][pixel] * r + inverse[1][pixel] * g + inverse[2][pixel] * b;
                green[pixel] = inverse[1][pixel] * r + inverse[3][pixel] * g + inverse[4][pixel] * b;
                blue[pixel] = inverse[2][pixel] * r + inverse[4][pixel] * g + inverse[5][pixel] * b;
                mean[pixel] -= red[pixel] * guideMean[0][pixel] + green[pixel] * guideMean[1][pixel]
                        + blue[pixel] * guideMean[2][pixel];
            }
            mean = box(mean, width, height, radius);
            for (int axis = 0; axis < 3; axis++) {
                float[] averaged = box(covariances[axis], width, height, radius);
                for (int pixel = 0; pixel < area; pixel++) mean[pixel] += averaged[pixel] * guide[axis][pixel];
            }
            styled[channel] = mean;
        }
    }

    private static float[] localCovariance(float[] left, float[] right, float[] meanLeft, float[] meanRight,
                                            int width, int height, int radius) {
        float[] products = new float[left.length];
        for (int i = 0; i < products.length; i++) products[i] = left[i] * right[i];
        float[] covariance = box(products, width, height, radius);
        for (int i = 0; i < covariance.length; i++) covariance[i] -= meanLeft[i] * meanRight[i];
        return covariance;
    }

    private static float[] box(float[] values, int width, int height, int radius) {
        int pitch = width + 1;
        double[] sum = new double[pitch * (height + 1)];
        for (int y = 0; y < height; y++) {
            double row = 0;
            for (int x = 0; x < width; x++) {
                row += values[y * width + x];
                sum[(y + 1) * pitch + x + 1] = row + sum[y * pitch + x + 1];
            }
        }
        float[] means = new float[values.length];
        for (int y = 0; y < height; y++) {
            int top = Math.max(0, y - radius), bottom = Math.min(height, y + radius + 1);
            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - radius), right = Math.min(width, x + radius + 1);
                double total = sum[bottom * pitch + right] - sum[bottom * pitch + left]
                        - sum[top * pitch + right] + sum[top * pitch + left];
                means[y * width + x] = (float) (total / ((right - left) * (bottom - top)));
            }
        }
        return means;
    }

    private static void validate(int[] pixels, int width, int height) throws IOException {
        if (pixels == null || width < 1 || height < 1 || pixels.length != (long) width * height) {
            throw new IOException("风格迁移输入尺寸无效");
        }
    }

    private static float bounded(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static float position(int pixel, int output, int input) {
        return Math.max(0, Math.min(input - 1, (pixel + .5f) * input / output - .5f));
    }

    private static float mix(float a, float b, float weight) {
        return a + (b - a) * weight;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("风格迁移已取消");
    }

    private record Feature(float[] values, int channels, int width, int height) {
        int area() { return width * height; }
        Feature copy() { return new Feature(values.clone(), channels, width, height); }
    }

    private record Sample(Feature tensor, int width, int height) {
        float[][] croppedChannels(Feature feature) {
            float[][] cropped = new float[3][width * height];
            int area = feature.area();
            for (int channel = 0; channel < 3; channel++) {
                for (int row = 0; row < height; row++) {
                    System.arraycopy(feature.values, channel * area + row * feature.width,
                            cropped[channel], row * width, width);
                }
            }
            return cropped;
        }
    }

    private record Coloring(float[] means, float[] root) {}

    private record Cached(Model model, int[] target, int targetWidth, int targetHeight,
                          int[] reference, int referenceWidth, int referenceHeight, int[] result) {
        boolean matches(Model other, int[] input, int width, int height, int[] style, int styleWidth, int styleHeight) {
            return model == other && targetWidth == width && targetHeight == height && referenceWidth == styleWidth
                    && referenceHeight == styleHeight && Arrays.equals(target, input) && Arrays.equals(reference, style);
        }
    }

    // Symmetric-only Householder and QL routines adapted from public-domain JAMA (NIST/MathWorks).
    // https://math.nist.gov/javanumerics/jama/
    private static final class SymmetricEigen {
        final double[][] V;
        final double[] d;
        private final double[] e;
        private final int n;

        SymmetricEigen(double[][] covariance) throws InterruptedIOException {
            V = covariance;
            n = covariance.length;
            d = new double[n];
            e = new double[n];
            tridiagonalize();
            solveTridiagonal();
        }

        private void tridiagonalize() throws InterruptedIOException {

        //  This is derived from the Algol procedures tred2 by
        //  Bowdler, Martin, Reinsch, and Wilkinson, Handbook for
        //  Auto. Comp., Vol.ii-Linear Algebra, and the corresponding
        //  Fortran subroutine in EISPACK.

           for (int j = 0; j < n; j++) {
              d[j] = V[n-1][j];
           }

           // Householder reduction to tridiagonal form.

           for (int i = n-1; i > 0; i--) {
              checkCancelled();

              // Scale to avoid under/overflow.

              double scale = 0.0;
              double h = 0.0;
              for (int k = 0; k < i; k++) {
                 scale = scale + Math.abs(d[k]);
              }
              if (scale == 0.0) {
                 e[i] = d[i-1];
                 for (int j = 0; j < i; j++) {
                    d[j] = V[i-1][j];
                    V[i][j] = 0.0;
                    V[j][i] = 0.0;
                 }
              } else {

                 // Generate Householder vector.

                 for (int k = 0; k < i; k++) {
                    d[k] /= scale;
                    h += d[k] * d[k];
                 }
                 double f = d[i-1];
                 double g = Math.sqrt(h);
                 if (f > 0) {
                    g = -g;
                 }
                 e[i] = scale * g;
                 h = h - f * g;
                 d[i-1] = f - g;
                 for (int j = 0; j < i; j++) {
                    e[j] = 0.0;
                 }

                 // Apply similarity transformation to remaining columns.

                 for (int j = 0; j < i; j++) {
                    f = d[j];
                    V[j][i] = f;
                    g = e[j] + V[j][j] * f;
                    for (int k = j+1; k <= i-1; k++) {
                       g += V[k][j] * d[k];
                       e[k] += V[k][j] * f;
                    }
                    e[j] = g;
                 }
                 f = 0.0;
                 for (int j = 0; j < i; j++) {
                    e[j] /= h;
                    f += e[j] * d[j];
                 }
                 double hh = f / (h + h);
                 for (int j = 0; j < i; j++) {
                    e[j] -= hh * d[j];
                 }
                 for (int j = 0; j < i; j++) {
                    f = d[j];
                    g = e[j];
                    for (int k = j; k <= i-1; k++) {
                       V[k][j] -= (f * e[k] + g * d[k]);
                    }
                    d[j] = V[i-1][j];
                    V[i][j] = 0.0;
                 }
              }
              d[i] = h;
           }

           // Accumulate transformations.

           for (int i = 0; i < n-1; i++) {
              V[n-1][i] = V[i][i];
              V[i][i] = 1.0;
              double h = d[i+1];
              if (h != 0.0) {
                 for (int k = 0; k <= i; k++) {
                    d[k] = V[k][i+1] / h;
                 }
                 for (int j = 0; j <= i; j++) {
                    double g = 0.0;
                    for (int k = 0; k <= i; k++) {
                       g += V[k][i+1] * V[k][j];
                    }
                    for (int k = 0; k <= i; k++) {
                       V[k][j] -= g * d[k];
                    }
                 }
              }
              for (int k = 0; k <= i; k++) {
                 V[k][i+1] = 0.0;
              }
           }
           for (int j = 0; j < n; j++) {
              d[j] = V[n-1][j];
              V[n-1][j] = 0.0;
           }
           V[n-1][n-1] = 1.0;
           e[0] = 0.0;
        }

        // Symmetric tridiagonal QL algorithm.

        private void solveTridiagonal() throws InterruptedIOException {

        //  This is derived from the Algol procedures tql2, by
        //  Bowdler, Martin, Reinsch, and Wilkinson, Handbook for
        //  Auto. Comp., Vol.ii-Linear Algebra, and the corresponding
        //  Fortran subroutine in EISPACK.

           for (int i = 1; i < n; i++) {
              e[i-1] = e[i];
           }
           e[n-1] = 0.0;

           double f = 0.0;
           double tst1 = 0.0;
           double eps = Math.pow(2.0,-52.0);
           for (int l = 0; l < n; l++) {
              checkCancelled();

              // Find small subdiagonal element

              tst1 = Math.max(tst1,Math.abs(d[l]) + Math.abs(e[l]));
              int m = l;
              while (m < n) {
                 if (Math.abs(e[m]) <= eps*tst1) {
                    break;
                 }
                 m++;
              }

              // If m == l, d[l] is an eigenvalue,
              // otherwise, iterate.

              if (m > l) {
                 int iter = 0;
                 do {
                    iter = iter + 1;  // (Could check iteration count here.)

                    // Compute implicit shift

                    double g = d[l];
                    double p = (d[l+1] - g) / (2.0 * e[l]);
                    double r = Math.hypot(p,1.0);
                    if (p < 0) {
                       r = -r;
                    }
                    d[l] = e[l] / (p + r);
                    d[l+1] = e[l] * (p + r);
                    double dl1 = d[l+1];
                    double h = g - d[l];
                    for (int i = l+2; i < n; i++) {
                       d[i] -= h;
                    }
                    f = f + h;

                    // Implicit QL transformation.

                    p = d[m];
                    double c = 1.0;
                    double c2 = c;
                    double c3 = c;
                    double el1 = e[l+1];
                    double s = 0.0;
                    double s2 = 0.0;
                    for (int i = m-1; i >= l; i--) {
                       c3 = c2;
                       c2 = c;
                       s2 = s;
                       g = c * e[i];
                       h = c * p;
                       r = Math.hypot(p,e[i]);
                       e[i+1] = s * r;
                       s = e[i] / r;
                       c = p / r;
                       p = c * d[i] - s * g;
                       d[i+1] = h + s * (c * g + s * d[i]);

                       // Accumulate transformation.

                       for (int k = 0; k < n; k++) {
                          h = V[k][i+1];
                          V[k][i+1] = s * V[k][i] + c * h;
                          V[k][i] = c * V[k][i] - s * h;
                       }
                    }
                    p = -s * s2 * c3 * el1 * e[l] / dl1;
                    e[l] = s * p;
                    d[l] = c * p;

                    // Check for convergence.

                 } while (Math.abs(e[l]) > eps*tst1);
              }
              d[l] = d[l] + f;
              e[l] = 0.0;
           }

           // Sort eigenvalues and corresponding vectors.

           for (int i = 0; i < n-1; i++) {
              int k = i;
              double p = d[i];
              for (int j = i+1; j < n; j++) {
                 if (d[j] < p) {
                    k = j;
                    p = d[j];
                 }
              }
              if (k != i) {
                 d[k] = d[i];
                 d[i] = p;
                 for (int j = 0; j < n; j++) {
                    p = V[j][i];
                    V[j][i] = V[j][k];
                    V[j][k] = p;
                 }
              }
           }
        }
    }
}
