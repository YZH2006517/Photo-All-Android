package org.photoall.film.retouch;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.IOException;

/**
 * The retouch engine as Photo All's editor uses it (the engine itself comes from ZRetouch,
 * by the same author): AI colour by one of five models, AI light by one of three, AI
 * denoise, and a reference photo's colours (仿色) by a classic match or one of two neural
 * ones; any mix of them at their own strengths, as a quick preview or at the picture's size.
 * Not thread-safe: one worker thread owns it.
 */
public final class Retouch implements AutoCloseable {
    /** 智能调色: the model that picks the picture's colour. */
    public enum Color {
        ADAPTIVE(RetouchRecipe.ColorModel.ADAPTIVE, "自适应"), SEPLUT(RetouchRecipe.ColorModel.SEPLUT, "SepLUT"),
        NEUROP(RetouchRecipe.ColorModel.NEUROP, "NeurOp"), ADAINT(RetouchRecipe.ColorModel.ADAINT, "AdaInt"),
        HDRNET(RetouchRecipe.ColorModel.HDRNET, "HDRNet");
        final RetouchRecipe.ColorModel model;
        public final String label;
        Color(RetouchRecipe.ColorModel model, String label) { this.model = model; this.label = label; }
    }

    /** 光线: the model that lifts a dark picture. */
    public enum Light {
        ZERO_DCE(RetouchRecipe.Light.ZERO_DCE, "Zero-DCE++"), IAT(RetouchRecipe.Light.IAT, "IAT"),
        RETINEXFORMER(RetouchRecipe.Light.RETINEXFORMER, "Retinexformer");
        final RetouchRecipe.Light model;
        public final String label;
        Light(RetouchRecipe.Light model, String label) { this.model = model; this.label = label; }
    }

    /** 仿色: how a reference photo's colours are carried over. */
    public enum Match {
        CLASSIC(RetouchRecipe.Match.CLASSIC, "经典"), FAST_PHOTO_STYLE(RetouchRecipe.Match.FAST_PHOTO_STYLE, "FastPhotoStyle"),
        WCT2(RetouchRecipe.Match.WCT2, "WCT2");
        final RetouchRecipe.Match model;
        public final String label;
        Match(RetouchRecipe.Match model, String label) { this.model = model; this.label = label; }
    }

    /** What to do: each strength 0..1 (0 leaves that step out), and the model for each step. */
    public static final class Plan {
        public float color, light, noise, match;
        public Color colorModel = Color.ADAPTIVE;
        public Light lightModel = Light.ZERO_DCE;
        public Match matchModel = Match.CLASSIC;

        public boolean any() { return color > 0 || light > 0 || noise > 0 || match > 0; }

        public Plan copy() {
            Plan p = new Plan();
            p.color = color; p.light = light; p.noise = noise; p.match = match;
            p.colorModel = colorModel; p.lightModel = lightModel; p.matchModel = matchModel;
            return p;
        }
    }

    private final Context context;
    private final OfflineEngine engine;
    private ReferenceLook reference;

    public Retouch(Context context) throws IOException {
        this.context = context.getApplicationContext();
        this.engine = new OfflineEngine(this.context);
    }

    /** The photo whose colours 仿色 carries over (kept with the app's files for the neural models). */
    public void setReference(Bitmap photo) throws IOException {
        reference = ReferenceLook.extract(context, photo);
        if (reference == null) throw new IOException("参考图里没有可用的颜色");
    }

    public boolean hasReference() { return reference != null; }

    /** Quick, for the screen: the heavy models run on a small copy and their change is laid over [source]. */
    public Bitmap preview(Bitmap source, Plan plan) throws Exception { return engine.preview(source, recipe(plan)); }

    /** At [source]'s own size, tile by tile. */
    public Bitmap full(Bitmap source, Plan plan) throws Exception { return full(source, plan, null); }

    /** [full], telling [progress] which step it is on and how far (for a long denoise at full size). */
    public Bitmap full(Bitmap source, Plan plan, java.util.function.Consumer<String> progress) throws Exception {
        OfflineEngine.progress = progress;
        try { return engine.renderFull(source, recipe(plan)); }
        finally { OfflineEngine.progress = null; }
    }

    private RetouchRecipe recipe(Plan p) {
        boolean match = reference != null && p.match > 0;
        int mask = (p.color > 0 ? RetouchRecipe.Model.COLOR.bit() : 0) | (p.light > 0 ? RetouchRecipe.Model.NIGHT.bit() : 0)
                | (p.noise > 0 ? RetouchRecipe.Model.DENOISE.bit() : 0) | (match ? RetouchRecipe.Model.MATCH.bit() : 0);
        RetouchRecipe r = RetouchRecipe.automatic(RetouchPreset.COLOR, 1f)
                .withModel(p.colorModel.model).withLight(p.lightModel.model).withNoise(RetouchRecipe.Noise.FFDNET)
                .withMatch(p.matchModel.model);
        if (match) r = r.withReference(reference.forPreset(p.matchModel.model), clamp(p.match));
        return r.withControls(mask, new float[]{clamp(p.color), clamp(p.light), clamp(p.noise), clamp(p.match)});
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    @Override public void close() throws Exception { engine.close(); }
}
