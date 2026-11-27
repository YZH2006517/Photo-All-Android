package org.photoall.film.retouch;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import static org.photoall.film.retouch.RetouchRecipe.Model.COLOR;
import static org.photoall.film.retouch.RetouchRecipe.Model.DENOISE;
import static org.photoall.film.retouch.RetouchRecipe.Model.MATCH;
import static org.photoall.film.retouch.RetouchRecipe.Model.NIGHT;

final class OfflineEngine implements AutoCloseable {
    // large tiles: a 12 MP denoise took 87 s in 192 x 128 tiles, 71 s in these (S23 Ultra, CPU;
    // NNAPI ran it no faster there)
    private static final int BAND = 384, TILE = 512, MARGIN = 26;

    /** Where a full-size render is (a step's name and its percent), for the save's veil; null: nobody asks. */
    static volatile java.util.function.Consumer<String> progress;
    private static volatile String stage = "";
    private static void report(int done, int total) {
        java.util.function.Consumer<String> p = progress;
        if (p != null && total > 0) p.accept(stage + " " + Math.min(99, done * 100 / total) + "%");
    }
    private static final int DENOISE_PREVIEW_EDGE = 640;
    private static final int LIGHT_PREVIEW_EDGE = 384;
    private static final int NEUROP_EDGE = 65, NEUROP_PLANES_PER_CALL = 4;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final Lut lut;
    private final Context context;
    private OrtSession classifier, sepLut, adaInt, neurOpPredictor, neurOpRenderer, curves, denoiser;
    private NAFDenoiser nafnet;
    private LightRestoration lightRestoration;
    private HdrNetColor hdrnet;
    private volatile Map<String, Float> adjustmentParameters = Collections.emptyMap();

    OfflineEngine(Context context) throws IOException {
        this.context = context.getApplicationContext();
        try (InputStream in = context.getAssets().open("offline/luts.bin")) {
            byte[] bytes = Images.bytes(in);
            if (bytes.length != 3 * 3 * 33 * 33 * 33 * 4) throw new IOException("离线 LUT 资源损坏");
            float[] values = new float[bytes.length / 4];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
            lut = new Lut(values);
        }
    }

    /** The whole picture at its own size: denoise, light, colour and reference look as [recipe] says. */
    Bitmap renderFull(Bitmap original, RetouchRecipe recipe) throws Exception {
        adjustmentParameters = Collections.emptyMap();
        Bitmap result = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        int width = result.getWidth();
        Map<String, Float> parameters = develop(new PhotoRaster.BitmapSource(original), recipe,
                (top, pixels, count) -> result.setPixels(pixels, 0, width, 0, top, width, count));
        adjustmentParameters = parameters;
        return result;
    }

    private Map<String, Float> develop(PhotoRaster original, RetouchRecipe recipe, Rows output) throws Exception {
        float noise = recipe.amount(DENOISE);
        if (noise <= 0) return finish(original, output, recipe);
        if (!recipe.hasAdjustments()) {
            denoise(original,recipe.noiseModel,noise,output);
            return Collections.emptyMap();
        }
        try (PhotoRaster.Scratch cleared = new PhotoRaster.Scratch(context.getCacheDir(), original.width, original.height)) {
            denoise(original, recipe.noiseModel, noise, (top, pixels, count) -> cleared.append(pixels,count));
            return finish(cleared, output, recipe);
        }
    }

    Bitmap preview(Bitmap original, RetouchRecipe recipe) throws Exception {
        adjustmentParameters = Collections.emptyMap();
        Map<String, Float> parameters = Collections.emptyMap();
        checkCancelled();
        Bitmap result = original.copy(Bitmap.Config.ARGB_8888, true);
        if (result == null) throw new IOException("无法生成预览");
        int width = result.getWidth();
        Rows writer = (top, pixels, count) -> result.setPixels(pixels, 0, width, 0, top, width, count);
        boolean finished = false;
        try {
            float noise = recipe.amount(DENOISE);
            if (noise > 0) previewDenoise(original, result, recipe.noiseModel, noise, writer);
            PhotoRaster preview = new PhotoRaster.BitmapSource(result);
            if (restoresLight(preview, recipe)) previewLight(preview, writer, recipe);
            if (recipe.hasAdjustments()) {
                parameters = render(preview, writer, recipe);
            }
            checkCancelled();
            adjustmentParameters = parameters;
            finished = true;
            return result;
        } finally {
            if (!finished) result.recycle();
        }
    }

    private void previewDenoise(Bitmap original, Bitmap result, RetouchRecipe.Noise model, float strength,
                                Rows writer) throws Exception {
        int width = original.getWidth(), height = original.getHeight();
        int edge = Math.max(width, height);
        if (edge <= DENOISE_PREVIEW_EDGE) {
            denoise(new PhotoRaster.BitmapSource(original), model, strength, writer);
            return;
        }
        float scale = DENOISE_PREVIEW_EDGE / (float) edge;
        int smallWidth = Math.max(1, Math.round(width * scale));
        int smallHeight = Math.max(1, Math.round(height * scale));
        int plane = smallWidth * smallHeight;
        int[] before = new int[plane];
        short[] residual = new short[plane * 3];
        Bitmap small = Bitmap.createScaledBitmap(original, smallWidth, smallHeight, true);
        try {
            small.getPixels(before, 0, smallWidth, 0, 0, smallWidth, smallHeight);
            denoise(new PhotoRaster.BitmapSource(small), model, strength, (top, pixels, rows) -> {
                int start = top * smallWidth;
                for (int i = 0; i < rows * smallWidth; i++) {
                    int offset = start + i;
                    for (int c = 0; c < 3; c++) {
                        int shift = (2 - c) * 8;
                        residual[c * plane + offset] = (short) ((pixels[i] >>> shift & 255) - (before[offset] >>> shift & 255));
                    }
                }
            });
        } finally {
            small.recycle();
        }
        // Upsample only the learned correction. The display bitmap keeps the detail in the original preview.
        int[] left = new int[width], right = new int[width], row = new int[width];
        float[] horizontal = new float[width];
        for (int x = 0; x < width; x++) {
            float point = samplePosition(x, width, smallWidth);
            left[x] = (int) point;
            right[x] = Math.min(smallWidth - 1, left[x] + 1);
            horizontal[x] = point - left[x];
        }
        for (int y = 0; y < height; y++) {
            checkCancelled();
            float point = samplePosition(y, height, smallHeight);
            int upper = (int) point, lower = Math.min(smallHeight - 1, upper + 1);
            float vertical = point - upper;
            original.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int input = row[x], adjusted = input & 0xff000000;
                if (adjusted == 0) continue;
                for (int c = 0; c < 3; c++) {
                    int shift = (2 - c) * 8, top = c * plane + upper * smallWidth, bottom = c * plane + lower * smallWidth;
                    float a = mix(residual[top + left[x]], residual[top + right[x]], horizontal[x]);
                    float b = mix(residual[bottom + left[x]], residual[bottom + right[x]], horizontal[x]);
                    int channel = Math.round((input >>> shift & 255) + mix(a, b, vertical));
                    adjusted |= Math.max(0, Math.min(255, channel)) << shift;
                }
                row[x] = adjusted;
            }
            result.setPixels(row, 0, width, 0, y, width, 1);
        }
    }

    private static float samplePosition(int pixel, int output, int input) {
        return Math.max(0, Math.min(input - 1, (pixel + .5f) * input / output - .5f));
    }

    /** Unitless NeurOp outputs before the app's color blend, available after a successful render. */
    Map<String, Float> adjustmentParameters() { return adjustmentParameters; }

    private Map<String, Float> finish(PhotoRaster source, Rows receiver, RetouchRecipe recipe) throws Exception {
        if (!restoresLight(source, recipe)) return render(source, receiver, recipe);
        if (recipe.amount(COLOR) <= 0 && recipe.amount(MATCH) <= 0) {
            restoreLight(source, recipe, receiver);
            return Collections.emptyMap();
        }
        try (PhotoRaster.Scratch lit = new PhotoRaster.Scratch(context.getCacheDir(), source.width, source.height)) {
            restoreLight(source, recipe, (top, pixels, count) -> lit.append(pixels, count));
            return render(lit, receiver, recipe);
        }
    }

    private boolean restoresLight(PhotoRaster source, RetouchRecipe recipe) throws IOException {
        if (recipe.amount(NIGHT) <= 0 || recipe.lightModel == RetouchRecipe.Light.ZERO_DCE) return false;
        if (!recipe.lightOnlyWhenDark) return true;
        int[] sample = source.sample(48, 48);
        long luminance = 0;
        for (int pixel : sample) luminance += luma(pixel);
        return luminance < sample.length * (255 * .35);
    }

    private void restoreLight(PhotoRaster source, RetouchRecipe recipe, Rows receiver) throws Exception {
        if (lightRestoration == null) lightRestoration = new LightRestoration(context);
        stage = "AI 光线";
        int size = LightRestoration.TILE;
        tiled(source, size, 32, recipe.amount(NIGHT), false,
                pixels -> lightRestoration.restore(recipe.lightModel, pixels, size, size), receiver);
    }

    private void previewLight(PhotoRaster source, Rows receiver, RetouchRecipe recipe) throws Exception {
        float scale = Math.min(1f, LIGHT_PREVIEW_EDGE / (float) Math.max(source.width, source.height));
        int width = Math.max(1, Math.round(source.width * scale));
        int height = Math.max(1, Math.round(source.height * scale));
        int[] sample = source.sample(width, height);
        int[] enhanced = new int[sample.length];
        Bitmap analysis = Bitmap.createBitmap(sample, width, height, Bitmap.Config.ARGB_8888);
        try {
            restoreLight(new PhotoRaster.BitmapSource(analysis), recipe, (top, pixels, count) ->
                    System.arraycopy(pixels, 0, enhanced, top * width, width * count));
        } finally {
            analysis.recycle();
        }
        StyleField light = new StyleField(sample, enhanced, width, height, 1);
        int[] pixels = new int[source.width * Math.min(BAND, source.height)];
        for (int top = 0; top < source.height; top += BAND) {
            checkCancelled();
            int count = Math.min(BAND, source.height - top);
            source.read(top, count, pixels);
            for (int row = 0; row < count; row++) {
                float y = (top + row + .5f) / source.height;
                int start = row * source.width;
                for (int x = 0; x < source.width; x++) {
                    pixels[start + x] = light.pixel(pixels[start + x], (x + .5f) / source.width, y);
                }
            }
            receiver.write(top, pixels, count);
        }
    }

    private Map<String, Float> render(PhotoRaster source, Rows receiver, RetouchRecipe recipe) throws Exception {
        checkCancelled();
        float amount = recipe.amount(NIGHT);
        Relight light = amount > 0 && recipe.lightModel == RetouchRecipe.Light.ZERO_DCE
                ? relight(source, amount, recipe.lightOnlyWhenDark) : null;
        ColorAdjustment adjustment = colorAdjustment(source, light, recipe);
        PixelAdjustment adjust = adjustment == null ? null : adjustment.pixel;
        int[] pixels = new int[source.width * Math.min(BAND, source.height)];
        for (int top = 0; top < source.height; top += BAND) {
            checkCancelled();
            int count = Math.min(BAND, source.height - top);
            source.read(top, count, pixels);
            for (int y = 0; y < count; y++) {
                int offset = y * source.width;
                if (light != null) light.row(pixels, offset, top + y);
                if (adjust != null) {
                    float row = (top + y + .5f) / source.height;
                    for (int x = 0; x < source.width; x++) {
                        pixels[offset + x] = adjust.apply(pixels[offset + x], (x + .5f) / source.width, row);
                    }
                }
            }
            receiver.write(top, pixels, count);
        }
        return adjustment == null ? Collections.emptyMap() : adjustment.parameters;
    }

    private ColorAdjustment colorAdjustment(PhotoRaster source, Relight light, RetouchRecipe recipe) throws Exception {
        float color = recipe.amount(COLOR), match = recipe.amount(MATCH);
        if (color <= 0 && match <= 0) return null;
        boolean neuralMatch = match > 0 && recipe.matchModel != RetouchRecipe.Match.CLASSIC;
        final int edge = 256;
        final int height = neuralMatch && source.width > source.height
                ? Math.max(1, Math.round(edge * (float) source.height / source.width)) : edge;
        // NeurOp's predictor preserves aspect ratio. Bound memory for extreme panoramas and strips.
        int width = color > 0 && recipe.colorModel == RetouchRecipe.ColorModel.NEUROP
                ? (int) Math.max(8, Math.min(768, (long) height * source.width / source.height))
                : neuralMatch && source.width < source.height
                ? Math.max(1, Math.round(edge * (float) source.width / source.height)) : edge;
        int[] pixels = source.sample(width, height);
        if (light != null) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = y * width + x;
                    pixels[at] = light.pixel(pixels[at], (x + .5f) / width, (y + .5f) / height);
                }
            }
        }
        ColorAdjustment automatic = color > 0 ? colorModel(pixels, width, height, recipe.colorModel, color) : null;
        if (match <= 0) return automatic;
        PixelAdjustment auto = automatic == null ? null : automatic.pixel;
        if (auto != null) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = y * width + x;
                    pixels[at] = auto.apply(pixels[at], (x + .5f) / width, (y + .5f) / height);
                }
            }
        }
        checkCancelled();
        PixelAdjustment reference;
        if (neuralMatch) {
            reference = neuralReference(recipe, pixels, width, height, match);
        } else {
            RgbLut table = recipe.reference.fit(pixels);
            if (table == null) return automatic;
            reference = (pixel, x, y) -> table.pixel(pixel, match);
        }
        if (auto == null) return new ColorAdjustment(reference, Collections.emptyMap());
        return new ColorAdjustment((pixel, x, y) -> reference.apply(auto.apply(pixel, x, y), x, y), automatic.parameters);
    }

    private PixelAdjustment neuralReference(RetouchRecipe recipe, int[] sample, int width, int height, float amount)
            throws IOException {
        Bitmap style = recipe.reference.image(context);
        try {
            int sw = style.getWidth(), sh = style.getHeight();
            int[] pixels = new int[sw * sh];
            style.getPixels(pixels, 0, sw, 0, 0, sw, sh);
            PhotoStyleTransfer.Model model = recipe.matchModel == RetouchRecipe.Match.WCT2
                    ? PhotoStyleTransfer.Model.WCT2 : PhotoStyleTransfer.Model.FAST_PHOTO_STYLE;
            int[] result = PhotoStyleTransfer.render(context, model, sample, width, height, pixels, sw, sh);
            return new StyleField(sample, result, width, height, amount)::pixel;
        } finally {
            style.recycle();
        }
    }

    private ColorAdjustment colorModel(int[] pixels, int width, int height, RetouchRecipe.ColorModel model,
                                       float strength) throws Exception {
        if (model == RetouchRecipe.ColorModel.HDRNET) {
            if (hdrnet == null) hdrnet = new HdrNetColor(context);
            HdrNetColor.Adjustment color = hdrnet.prepare(pixels, width, height, strength);
            return new ColorAdjustment(color::apply, Collections.emptyMap());
        }
        if (model == RetouchRecipe.ColorModel.NEUROP) return neurOp(pixels, width, height, strength);
        if (model == RetouchRecipe.ColorModel.ADAINT) {
            final int edge = 33;
            if (adaInt == null) adaInt = open("adaint_fivek_srgb.onnx");
            try (OnnxTensor input = tensor(rgb(pixels), width, height);
                 OrtSession.Result result = adaInt.run(Collections.singletonMap("image", input))) {
                float[] vertices = output(result, 0, 3 * edge), cube = output(result, 1, 3 * edge * edge * edge);
                RgbLut table = RgbLut.adaptive(edge, cube, vertices);
                return new ColorAdjustment(pixel -> table.pixel(pixel, strength));
            }
        }
        if (model == RetouchRecipe.ColorModel.SEPLUT) {
            if (sepLut == null) sepLut = open("seplut_small.onnx");
            try (OnnxTensor input = tensor(rgb(pixels), width, height);
                 OrtSession.Result result = sepLut.run(Collections.singletonMap("image", input))) {
                float[] curves = output(result, 0, 27), cube = output(result, 1, 2187);
                RgbLut table = new RgbLut(9, cube, curves);
                return new ColorAdjustment(pixel -> table.pixel(pixel, strength));
            }
        }
        if (classifier == null) classifier = open("adaptive_classifier.onnx");
        try (OnnxTensor input = tensor(rgb(pixels), width, height);
             OrtSession.Result result = classifier.run(Collections.singletonMap("image", input))) {
            float[] weights = output(result, 3);
            Lut.validate(weights);
            return new ColorAdjustment(pixel -> lut.pixel(pixel, weights, strength));
        }
    }

    private ColorAdjustment neurOp(int[] pixels, int width, int height, float blend) throws Exception {
        if (neurOpPredictor == null) neurOpPredictor = open("neurop_predictor.onnx");
        float[] values;
        try (OnnxTensor input = tensor(rgb(pixels), width, height);
             OrtSession.Result result = neurOpPredictor.run(Collections.singletonMap("image", input))) {
            values = output(result, 3);
        }
        for (float value : values) {
            if (!Float.isFinite(value) || Math.abs(value) > 1) throw new IOException("NeurOp 参数无效");
        }
        if (neurOpRenderer == null) neurOpRenderer = open("neurop_renderer.onnx");
        int edge = NEUROP_EDGE, plane = edge * edge, volume = plane * edge;
        float[] cube = new float[3 * volume];
        // The three neural operators are pointwise. Sample the RGB cube in bounded blue slabs.
        try (OnnxTensor strengths = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{1, 3})) {
            for (int start = 0; start < edge; start += NEUROP_PLANES_PER_CALL) {
                checkCancelled();
                int count = Math.min(edge - start, NEUROP_PLANES_PER_CALL), slab = count * plane;
                float[] lattice = new float[3 * slab];
                for (int i = 0; i < slab; i++) {
                    lattice[i] = (i % edge) / (edge - 1f);
                    lattice[slab + i] = (i / edge % edge) / (edge - 1f);
                    lattice[2 * slab + i] = (start + i / plane) / (edge - 1f);
                }
                try (OnnxTensor input = tensor(lattice, edge, count * edge)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("image", input);
                    inputs.put("strengths", strengths);
                    try (OrtSession.Result result = neurOpRenderer.run(inputs)) {
                        float[] enhanced = output(result, 3 * slab);
                        for (int channel = 0; channel < 3; channel++) {
                            System.arraycopy(enhanced, channel * slab, cube, channel * volume + start * plane, slab);
                        }
                    }
                }
            }
        }
        RgbLut table = new RgbLut(edge, cube, null);
        Map<String, Float> parameters = new LinkedHashMap<>();
        parameters.put("黑位", values[0]);
        parameters.put("曝光", values[1]);
        parameters.put("鲜艳度", values[2]);
        return new ColorAdjustment(pixel -> table.pixel(pixel, blend), Collections.unmodifiableMap(parameters));
    }

    private Relight relight(PhotoRaster source, float strength, boolean onlyDark) throws Exception {
        float scale = Math.min(1f / 12f, 512f / Math.max(source.width, source.height));
        int width = Math.max(1, Math.round(scale * source.width));
        int height = Math.max(1, Math.round(scale * source.height));
        int[] pixels = source.sample(width, height);
        float[] input = rgb(pixels);
        if (onlyDark) {
            double sum = 0;
            for (int i = 0; i < pixels.length; i++) {
                sum += .2126 * input[i] + .7152 * input[pixels.length + i] + .0722 * input[2 * pixels.length + i];
            }
            if (sum / pixels.length >= .35) return null;
        }
        if (curves == null) curves = open("zero_dce_curves.onnx");
        try (OnnxTensor tensor = tensor(input, width, height);
             OrtSession.Result result = curves.run(Collections.singletonMap("image", tensor))) {
            return new Relight(output(result, input.length), width, height, source.width, source.height, strength);
        }
    }

    private void denoise(PhotoRaster source, RetouchRecipe.Noise model, float strength, Rows receiver) throws Exception {
        if (model == RetouchRecipe.Noise.NAFNET) {
            if (nafnet == null) nafnet = new NAFDenoiser(context);
            int size = NAFDenoiser.TILE;
            stage = "AI 降噪";
            tiled(source, size, 32, strength, true, pixels -> nafnet.apply(pixels, size, size), receiver);
            return;
        }
        if (denoiser == null) denoiser = open("ffdnet_rgb.onnx");
        int width = source.width, height = source.height;
        int[] contextPixels = new int[width * Math.min(height, BAND + 2 * MARGIN)];
        int[] restoredPixels = new int[width * Math.min(height, BAND)];
        float noiseLevel = (4f + 8f * strength) / 255f;
        stage = "AI 降噪";
        try (OnnxTensor sigma = OnnxTensor.createTensor(environment, FloatBuffer.wrap(new float[]{noiseLevel}),
                new long[]{1, 1, 1, 1})) {
            for (int top = 0; top < height; top += BAND) {
                checkCancelled();
                int bottom = Math.min(height, top + BAND);
                int startY = Math.max(0, top - MARGIN);
                int endY = Math.min(height, bottom + MARGIN);
                source.read(startY, endY - startY, contextPixels);
                // every tile the same shape, the picture's edge repeated past it: an accelerator
                // (NNAPI) compiles the model once instead of once per edge tile
                int h = BAND + 2 * MARGIN;
                int originY = top - MARGIN;
                for (int left = 0; left < width; left += TILE) {
                    checkCancelled();
                    int right = Math.min(width, left + TILE);
                    // Even origins align every tile with FFDNet's global 2×2 lattice.
                    int originX = left - MARGIN;
                    int w = TILE + 2 * MARGIN;
                    int[] tile = new int[w * h];
                    for (int y = 0; y < h; y++) {
                        int row = (Math.max(startY, Math.min(endY - 1, originY + y)) - startY) * width;
                        for (int x = 0; x < w; x++)
                            tile[y * w + x] = contextPixels[row + Math.max(0, Math.min(width - 1, originX + x))];
                    }
                    try (OnnxTensor input = tensor(rgb(tile), w, h)) {
                        Map<String, OnnxTensor> inputs = new HashMap<>();
                        inputs.put("image", input);
                        inputs.put("sigma", sigma);
                        try (OrtSession.Result result = denoiser.run(inputs)) {
                            float[] filtered = output(result, w * h * 3);
                            for (int y = top; y < bottom; y++) {
                                int destination = (y - top) * width;
                                int from = (y - originY) * w - originX;
                                for (int x = left; x < right; x++) {
                                    int at = from + x;
                                    int pixel = tile[at], newPixel = pixel & 0xff000000;
                                    int localX = x - originX, localY = y - originY;
                                    float blend = strength * textureWeight(tile, w, h, at, localX, localY);
                                    for (int channel = 0; channel < 3; channel++) {
                                        int shift = (2 - channel) * 8;
                                        float adjusted = Math.max(0, Math.min(1, filtered[channel * w * h + at]));
                                        newPixel |= byteValue(mix(((pixel >>> shift) & 255) / 255f, adjusted, blend)) << shift;
                                    }
                                    restoredPixels[destination + x] = newPixel;
                                }
                            }
                        }
                    }
                }
                receiver.write(top, restoredPixels, bottom - top);
                report(bottom, height);
            }
        }
    }

    private static void tiled(PhotoRaster source, int tile, int margin, float strength, boolean preserveTexture,
                              TileModel model, Rows receiver) throws Exception {
        int width = source.width, height = source.height, core = tile - 2 * margin, feather = margin / 2;
        int[] inputRows = new int[width * Math.min(height, tile)];
        int[] band = new int[width * Math.min(height, core + 2 * feather)];
        int[] overlap = new int[width * Math.min(height, 2 * feather)];
        int[] input = new int[tile * tile];
        int carriedRows = 0;
        for (int top = 0; top < height; top += core) {
            checkCancelled();
            report(top, height);
            int sourceTop = Math.max(0, top - margin);
            int sourceBottom = Math.min(height, top + core + margin);
            source.read(sourceTop, sourceBottom - sourceTop, inputRows);
            int outputTop = Math.max(0, top - feather);
            int outputBottom = Math.min(height, top + core + feather);
            for (int left = 0; left < width; left += core) {
                checkCancelled();
                for (int y = 0; y < tile; y++) {
                    int row = (Math.max(0, Math.min(height - 1, top - margin + y)) - sourceTop) * width;
                    for (int x = 0; x < tile; x++) {
                        int column = Math.max(0, Math.min(width - 1, left - margin + x));
                        input[y * tile + x] = inputRows[row + column];
                    }
                }
                int[] restored = model.run(input);
                if (restored.length != input.length) throw new IOException("模型输出尺寸无效");
                int outputLeft = Math.max(0, left - feather);
                int outputRight = Math.min(width, left + core + feather);
                for (int y = outputTop; y < outputBottom; y++) {
                    int imageRow = (y - outputTop) * width, localY = y - top + margin;
                    for (int x = outputLeft; x < outputRight; x++) {
                        int localX = x - left + margin, index = localY * tile + localX;
                        int before = input[index];
                        float mix = preserveTexture ? strength * textureWeight(input, tile, tile, index, localX, localY) : strength;
                        int pixel = blend(before, restored[index], mix);
                        int offset = imageRow + x;
                        if (left != 0 && x < left + feather) {
                            pixel = blend(band[offset], pixel, (x - left + feather + .5f) / (2 * feather));
                        }
                        band[offset] = pixel;
                    }
                }
            }
            for (int y = 0; y < carriedRows; y++) {
                float weight = (y + .5f) / (2 * feather);
                for (int x = y * width; x < (y + 1) * width; x++) {
                    band[x] = blend(overlap[x], band[x], weight);
                }
            }
            int next = top + core < height ? top + core - feather : height;
            int completeRows = next - outputTop;
            receiver.write(outputTop, band, completeRows);
            carriedRows = outputBottom - next;
            if (carriedRows > 0) System.arraycopy(band, completeRows * width, overlap, 0, carriedRows * width);
        }
    }

    private static int blend(int before, int after, float amount) {
        int result = before & 0xff000000;
        if (result == 0 || amount <= 0) return before;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = before >>> shift & 255, b = after >>> shift & 255;
            result |= Math.round(mix(a, b, amount)) << shift;
        }
        return result;
    }

    private static float textureWeight(int[] tile, int width, int height, int at, int x, int y) {
        int left = x > 0 ? at - 1 : at, right = x + 1 < width ? at + 1 : at;
        int top = y > 0 ? at - width : at, bottom = y + 1 < height ? at + width : at;
        int contrast = Math.max(Math.abs(luma(tile[left]) - luma(tile[right])),
                Math.abs(luma(tile[top]) - luma(tile[bottom])));
        return 1f - .7f * Math.min(1, Math.max(0, (contrast - 14) / 42f));
    }

    private static int luma(int pixel) {
        return (77 * (pixel >>> 16 & 255) + 150 * (pixel >>> 8 & 255) + 29 * (pixel & 255)) >>> 8;
    }

    private OrtSession open(String asset) throws Exception {
        byte[] model;
        try (InputStream in = context.getAssets().open("offline/" + asset)) { model = Images.bytes(in); }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            return environment.createSession(model, options);
        }
    }

    private OnnxTensor tensor(float[] values, int width, int height) throws OrtException {
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{1, 3, height, width});
    }

    private static float[] output(OrtSession.Result result, int length) throws IOException {
        return output(result, 0, length);
    }

    private static float[] output(OrtSession.Result result, int index, int length) throws IOException {
        if (!(result.get(index) instanceof OnnxTensor tensor)) throw new IOException("模型输出无效");
        FloatBuffer buffer = tensor.getFloatBuffer();
        if (buffer.remaining() != length) throw new IOException("模型输出尺寸无效");
        float[] values = new float[length];
        buffer.get(values);
        return values;
    }

    private static float[] rgb(int[] pixels) {
        int count = pixels.length;
        float[] values = new float[count * 3];
        for (int i = 0; i < count; i++) {
            values[i] = ((pixels[i] >>> 16) & 255) / 255f;
            values[count + i] = ((pixels[i] >>> 8) & 255) / 255f;
            values[2 * count + i] = (pixels[i] & 255) / 255f;
        }
        return values;
    }

    private static float mix(float from, float to, float amount) { return from + amount * (to - from); }
    private static int byteValue(float value) { return Math.round(Math.max(0, Math.min(1, value)) * 255); }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("修图已暂停");
    }

    private interface Rows { void write(int top, int[] pixels, int count) throws IOException; }

    private interface TileModel { int[] run(int[] argb) throws Exception; }

    private interface PixelAdjustment { int apply(int pixel, float x, float y); }

    private record ColorAdjustment(PixelAdjustment pixel, Map<String, Float> parameters) {
        ColorAdjustment(IntUnaryOperator pixel) { this(pixel, Collections.emptyMap()); }
        ColorAdjustment(IntUnaryOperator pixel, Map<String, Float> parameters) {
            this((value, x, y) -> pixel.applyAsInt(value), parameters);
        }
    }

    /** Reapply sampled color changes to the source pixels, guided by their original brightness. */
    private static final class StyleField {
        private final int width, height;
        private final int[] guidance;
        private final short[] delta;
        private final float strength;

        StyleField(int[] input, int[] output, int width, int height, float strength) throws IOException {
            if (output.length != input.length) throw new IOException("仿色模型输出尺寸无效");
            this.width = width;
            this.height = height;
            this.strength = strength;
            guidance = new int[input.length];
            delta = new short[input.length * 3];
            for (int i = 0; i < input.length; i++) {
                guidance[i] = luma(input[i]);
                for (int channel = 0; channel < 3; channel++) {
                    int shift = 8 * channel;
                    delta[i * 3 + channel] = (short) ((output[i] >>> shift & 255) - (input[i] >>> shift & 255));
                }
            }
        }

        int pixel(int input, float x, float y) {
            int output = input & 0xff000000;
            if (output == 0) return input;
            float column = Relight.coordinate(x, width), row = Relight.coordinate(y, height);
            int left = (int) column, top = (int) row;
            int right = Math.min(width - 1, left + 1), bottom = Math.min(height - 1, top + 1);
            float dx = column - left, dy = row - top;
            int a = top * width + left, b = top * width + right, c = bottom * width + left, d = bottom * width + right;
            int guide = luma(input);
            float wa = (1 - dx) * (1 - dy) / (20 + Math.abs(guide - guidance[a]));
            float wb = dx * (1 - dy) / (20 + Math.abs(guide - guidance[b]));
            float wc = (1 - dx) * dy / (20 + Math.abs(guide - guidance[c]));
            float wd = dx * dy / (20 + Math.abs(guide - guidance[d]));
            float amount = strength / (wa + wb + wc + wd);
            for (int channel = 0; channel < 3; channel++) {
                float correction = wa * delta[a * 3 + channel] + wb * delta[b * 3 + channel]
                        + wc * delta[c * 3 + channel] + wd * delta[d * 3 + channel];
                int shift = 8 * channel;
                int value = Math.round((input >>> shift & 255) + amount * correction);
                output |= Math.max(0, Math.min(255, value)) << shift;
            }
            return output;
        }
    }

    private static final class Relight {
        private final float[] curves;
        private final int width, height, imageHeight;
        private final float strength;
        private final int[] left, right;
        private final float[] fraction;

        Relight(float[] curves, int width, int height, int imageWidth, int imageHeight, float strength) {
            this.curves = curves;
            this.width = width;
            this.height = height;
            this.imageHeight = imageHeight;
            this.strength = strength;
            left = new int[imageWidth];
            right = new int[imageWidth];
            fraction = new float[imageWidth];
            for (int x = 0; x < imageWidth; x++) {
                float point = coordinate((x + .5f) / imageWidth, width);
                left[x] = (int) point;
                right[x] = Math.min(width - 1, left[x] + 1);
                fraction[x] = point - left[x];
            }
        }

        void row(int[] pixels, int start, int y) {
            float row = coordinate((y + .5f) / imageHeight, height);
            int top = (int) row, bottom = Math.min(height - 1, top + 1);
            for (int x = 0; x < left.length; x++) {
                pixels[start + x] = apply(pixels[start + x], left[x], right[x], top, bottom, fraction[x], row - top);
            }
        }

        int pixel(int pixel, float x, float y) {
            float column = coordinate(x, width), row = coordinate(y, height);
            int l = (int) column, t = (int) row;
            return apply(pixel, l, Math.min(width - 1, l + 1), t, Math.min(height - 1, t + 1), column - l, row - t);
        }

        private int apply(int pixel, int left, int right, int top, int bottom, float x, float y) {
            int adjusted = pixel & 0xff000000;
            for (int channel = 0; channel < 3; channel++) {
                int plane = channel * width * height, shift = (2 - channel) * 8;
                float a = mix(curves[plane + top * width + left], curves[plane + top * width + right], x);
                float b = mix(curves[plane + bottom * width + left], curves[plane + bottom * width + right], x);
                float curve = mix(a, b, y), before = ((pixel >>> shift) & 255) / 255f, value = before;
                for (int step = 0; step < 8; step++) value += curve * (value * value - value);
                adjusted |= byteValue(mix(before, value, strength)) << shift;
            }
            return adjusted;
        }

        private static float coordinate(float normalized, int size) {
            return Math.max(0, Math.min(size - 1, normalized * size - .5f));
        }
    }

    @Override public void close() throws OrtException {
        OrtException failure = null;
        for (OrtSession session : new OrtSession[]{curves, denoiser, classifier, sepLut, adaInt, neurOpPredictor, neurOpRenderer}) {
            if (session == null) continue;
            try {
                session.close();
            } catch (OrtException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        for (AutoCloseable model : new AutoCloseable[]{lightRestoration, nafnet, hdrnet}) {
            if (model == null) continue;
            try {
                model.close();
            } catch (Exception error) {
                if (failure == null) {
                    failure = new OrtException("无法释放离线模型");
                }
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
