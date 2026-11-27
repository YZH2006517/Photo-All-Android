package org.photoall.film.retouch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Position is relative to the oriented photo. Size is relative to its shorter side. */
record StickerLayer(String id, float x, float y, float size, float degrees, Edge edge, int thickness) {
    enum Edge {
        WHITE("白边"), INK("墨线"), PAPER("双层纸边"), NONE("原边");
        final String label;
        Edge(String label) { this.label = label; }
    }

    StickerLayer {
        if (!UUID.fromString(id).toString().equals(id) || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(size) || !Float.isFinite(degrees) || size <= 0 || size > 3
                || edge == null || thickness < 0 || thickness > 100) throw new IllegalArgumentException("贴纸参数无效");
    }

    static StickerLayer place(String id) { return new StickerLayer(id, .5f, .5f, .42f, 0, Edge.WHITE, 45); }
    StickerLayer move(float cx, float cy, float scale, float angle) {
        return new StickerLayer(id, Math.max(0, Math.min(1, cx)), Math.max(0, Math.min(1, cy)),
                Math.max(.05f, Math.min(3, scale)), angle % 360, edge, thickness);
    }
    StickerLayer style(Edge next, int width) { return new StickerLayer(id, x, y, size, degrees, next, width); }

    static String encode(List<StickerLayer> layers) {
        ArrayList<String> entries = new ArrayList<>();
        for (StickerLayer s : layers) entries.add(String.join(":", s.id, Float.toString(s.x), Float.toString(s.y),
                Float.toString(s.size), Float.toString(s.degrees), s.edge.name(), Integer.toString(s.thickness)));
        return String.join(";", entries);
    }

    static List<StickerLayer> decode(String text) {
        if (text.isEmpty()) return Collections.emptyList();
        ArrayList<StickerLayer> result = new ArrayList<>();
        for (String entry : text.split(";")) {
            String[] v = entry.split(":", -1);
            if (v.length != 7) throw new IllegalArgumentException("贴纸记录无效");
            result.add(new StickerLayer(v[0], Float.parseFloat(v[1]), Float.parseFloat(v[2]), Float.parseFloat(v[3]),
                    Float.parseFloat(v[4]), Edge.valueOf(v[5]), Integer.parseInt(v[6])));
        }
        return Collections.unmodifiableList(result);
    }
}
