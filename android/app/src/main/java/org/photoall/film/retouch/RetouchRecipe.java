package org.photoall.film.retouch;

/** An immutable snapshot of the model switches and blend strengths. */
final class RetouchRecipe {
    enum Model {
        COLOR("智能调色", ""),
        NIGHT("暗光提亮", ""),
        DENOISE("细节降噪", ""),
        MATCH("参考图跟色", "");

        final String label, name;
        Model(String label, String name) { this.label = label; this.name = name; }
        int bit() { return 1 << ordinal(); }
    }

    enum ColorModel {
        ADAPTIVE("adaptive", "Image-Adaptive-3DLUT"),
        SEPLUT("seplut", "SepLUT-S"),
        NEUROP("neurop", "NeurOp"),
        ADAINT("adaint", "AdaInt"),
        HDRNET("hdrnet", "HDRNet");

        final String id, name;
        ColorModel(String id, String name) { this.id = id; this.name = name; }
        static ColorModel from(String id) {
            for (ColorModel model : values()) if (model.id.equals(id)) return model;
            return ADAPTIVE;
        }
    }

    enum Light {
        ZERO_DCE("Zero-DCE++"), IAT("IAT"), RETINEXFORMER("Retinexformer");

        final String label;
        Light(String label) { this.label = label; }
    }

    enum Noise {
        FFDNET("FFDNet"), NAFNET("NAFNet-SIDD");

        final String label;
        Noise(String label) { this.label = label; }
    }

    enum Match {
        CLASSIC("经典仿色"), FAST_PHOTO_STYLE("FastPhotoStyle"), WCT2("WCT2");

        final String label;
        Match(String label) { this.label = label; }
    }

    final boolean paper;
    final java.util.List<StickerLayer> stickers;
    final int enabled;
    final boolean lightOnlyWhenDark;
    final ColorModel colorModel;
    final Light lightModel;
    final Noise noiseModel;
    final Match matchModel;
    final ReferenceLook reference;
    private final float color, light, noise, match;

    private RetouchRecipe(int enabled, float color, float light, float noise, float match,
                          boolean lightOnlyWhenDark, ColorModel colorModel, ReferenceLook reference,
                          Light lightModel, Noise noiseModel, Match matchModel, java.util.List<StickerLayer> stickers, boolean paper) {
        this.paper = paper;
        this.stickers = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(stickers));
        Domain.validateStrength(color);
        Domain.validateStrength(light);
        Domain.validateStrength(noise);
        Domain.validateStrength(match);
        if ((enabled & ~15) != 0) throw new IllegalArgumentException("修图方案无效");
        this.enabled = reference == null ? enabled & ~Model.MATCH.bit() : enabled;
        this.color = color;
        this.light = light;
        this.noise = noise;
        this.match = match;
        this.lightOnlyWhenDark = lightOnlyWhenDark;
        this.colorModel = colorModel;
        this.reference = reference;
        this.lightModel = lightModel;
        this.noiseModel = noiseModel;
        this.matchModel = reference == null || reference.hasImage() ? matchModel : Match.CLASSIC;
    }

    static RetouchRecipe automatic(RetouchPreset preset, float strength) {
        if (preset == null) preset = RetouchPreset.COLOR;
        int enabled = switch (preset) {
            case COLOR -> Model.COLOR.bit();
            case NIGHT -> Model.NIGHT.bit();
            case DENOISE -> Model.DENOISE.bit();
            case COMPLETE -> Model.COLOR.bit() | Model.NIGHT.bit() | Model.DENOISE.bit();
        };
        return new RetouchRecipe(enabled, strength, preset == RetouchPreset.COMPLETE ? strength * .65f : strength,
                strength, .7f, preset == RetouchPreset.COMPLETE, ColorModel.ADAPTIVE, null,
                Light.ZERO_DCE, Noise.FFDNET, Match.CLASSIC, java.util.Collections.emptyList(), false);
    }

    boolean enabled(Model model) { return (enabled & model.bit()) != 0; }

    float level(Model model) {
        return switch (model) { case COLOR -> color; case NIGHT -> light; case DENOISE -> noise; case MATCH -> match; };
    }

    float amount(Model model) { return enabled(model) ? level(model) : 0; }

    RetouchRecipe manual() {
        return lightOnlyWhenDark ? new RetouchRecipe(enabled, color, light, noise, match, false, colorModel,
                reference, lightModel, noiseModel, matchModel, stickers, paper) : this;
    }

    RetouchRecipe withModel(ColorModel choice) {
        return new RetouchRecipe(enabled, color, light, noise, match, lightOnlyWhenDark, choice,
                reference, lightModel, noiseModel, matchModel, stickers, paper);
    }

    RetouchRecipe withLight(Light choice) {
        return new RetouchRecipe(enabled, color, light, noise, match, lightOnlyWhenDark, colorModel,
                reference, choice, noiseModel, matchModel, stickers, paper);
    }

    RetouchRecipe withNoise(Noise choice) {
        return new RetouchRecipe(enabled, color, light, noise, match, lightOnlyWhenDark, colorModel,
                reference, lightModel, choice, matchModel, stickers, paper);
    }

    RetouchRecipe withMatch(Match choice) {
        return new RetouchRecipe(enabled, color, light, noise, match, lightOnlyWhenDark, colorModel,
                reference, lightModel, noiseModel, choice, stickers, paper);
    }

    RetouchRecipe withReference(ReferenceLook look) {
        return withReference(look, match);
    }

    RetouchRecipe withReference(ReferenceLook look, float strength) {
        return new RetouchRecipe(enabled | Model.MATCH.bit(), color, light, noise, strength, lightOnlyWhenDark,
                colorModel, look, lightModel, noiseModel, matchModel, stickers, paper);
    }

    RetouchRecipe withControls(int mask, float[] levels) {
        return new RetouchRecipe(mask, levels[0], levels[1], levels[2], levels[3], false, colorModel,
                reference, lightModel, noiseModel, matchModel, stickers, paper);
    }

    RetouchRecipe withComposition(java.util.List<StickerLayer> placed, boolean mounted) {
        return new RetouchRecipe(enabled, color, light, noise, match, lightOnlyWhenDark, colorModel,
                reference, lightModel, noiseModel, matchModel, placed, mounted);
    }

    boolean active() {
        return paper || !stickers.isEmpty() || amount(Model.DENOISE) > 0 || hasAdjustments();
    }

    boolean hasAdjustments() {
        return amount(Model.COLOR) > 0 || amount(Model.NIGHT) > 0 || amount(Model.MATCH) > 0;
    }

    String summary() {
        StringBuilder label = new StringBuilder();
        for (Model model : Model.values()) {
            if (amount(model) <= 0) continue;
            if (label.length() != 0) label.append(" · ");
            label.append(modelName(model))
                    .append(' ').append(Math.round(amount(model) * 100)).append('%');
        }
        if (!stickers.isEmpty()) {
            if (label.length() > 0) label.append(" · ");
            label.append("贴纸 × ").append(stickers.size());
        }
        if (paper) { if (label.length() > 0) label.append(" · "); label.append("相纸"); }
        return label.toString();
    }

    String modelName(Model model) {
        return switch (model) {
            case COLOR -> colorModel.name;
            case NIGHT -> lightModel.label;
            case DENOISE -> noiseModel.label;
            case MATCH -> matchModel.label;
        };
    }

    String encode() {
        return enabled + "," + color + "," + light + "," + noise + "," + lightOnlyWhenDark + ","
                + colorModel.id + "," + match + "," + (reference == null ? "" : reference.encode()) + ","
                + lightModel.name() + "," + noiseModel.name() + "," + matchModel.name()
                + (stickers.isEmpty() && !paper ? "" : "," + StickerLayer.encode(stickers) + (paper ? ",paper" : ""));
    }

    static RetouchRecipe decode(String encoded) {
        if (encoded == null) return null;
        String[] values = encoded.split(",", -1);
        if (values.length != 5 && values.length != 8 && values.length != 11 && values.length != 12 && values.length != 13) return null;
        try {
            boolean expanded = values.length >= 8;
            boolean updated = values.length >= 11;
            return new RetouchRecipe(Integer.parseInt(values[0]), Float.parseFloat(values[1]),
                    Float.parseFloat(values[2]), Float.parseFloat(values[3]), expanded ? Float.parseFloat(values[6]) : .7f,
                    Boolean.parseBoolean(values[4]), expanded ? ColorModel.from(values[5]) : ColorModel.ADAPTIVE,
                    expanded ? ReferenceLook.decode(values[7]) : null,
                    updated ? Light.valueOf(values[8]) : Light.ZERO_DCE,
                    updated ? Noise.valueOf(values[9]) : Noise.FFDNET,
                    updated ? Match.valueOf(values[10]) : Match.CLASSIC,
                    values.length >= 12 ? StickerLayer.decode(values[11]) : java.util.Collections.emptyList(),
                    values.length == 13 && values[12].equals("paper"));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
