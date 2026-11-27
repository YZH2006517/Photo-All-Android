package org.photoall.film.retouch;

enum RetouchPreset {
    COLOR("color", "智能调色"),
    NIGHT("night", "暗光提亮"),
    DENOISE("denoise", "细节降噪"),
    COMPLETE("complete", "综合修图");

    final String id, label;
    RetouchPreset(String id, String label) {
        this.id = id;
        this.label = label;
    }

    static RetouchPreset from(String id) {
        for (RetouchPreset preset : values()) if (preset.id.equals(id)) return preset;
        return COLOR;
    }

    static String[] labels() {
        RetouchPreset[] presets = values();
        String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++) labels[i] = presets[i].label;
        return labels;
    }
}
