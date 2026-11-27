package org.photoall.film.retouch;

/** Trilinear [channel][blue][green][red] LUT, with optional channel curves. */
final class RgbLut {
    private final int edge, slice, volume;
    private final float[] cube;
    private final int[] lower = new int[3 * 256];
    private final float[] fractions = new float[3 * 256];

    RgbLut(int edge, float[] cube, float[] curves) {
        this(edge, cube, curves, null);
    }

    static RgbLut adaptive(int edge, float[] cube, float[] vertices) {
        return new RgbLut(edge, cube, null, vertices);
    }

    private RgbLut(int edge, float[] cube, float[] curves, float[] vertices) {
        this.edge = edge;
        this.cube = cube;
        slice = edge * edge;
        volume = slice * edge;
        if (edge < 2 || cube.length != 3 * volume || (curves != null && curves.length != 3 * edge)
                || (vertices != null && vertices.length != 3 * edge)) {
            throw new IllegalArgumentException("调色表尺寸无效");
        }
        for (float value : cube) if (!Float.isFinite(value)) throw new IllegalArgumentException("调色表无效");
        if (curves != null) {
            for (float value : curves) if (!Float.isFinite(value)) throw new IllegalArgumentException("调色曲线无效");
        }
        for (int channel = 0; channel < 3; channel++) {
            int offset = channel * edge;
            int vertex = 0;
            if (vertices != null) validateVertices(vertices, offset, edge);
            for (int value = 0; value < 256; value++) {
                int coordinate = channel * 256 + value;
                if (vertices != null) {
                    float point = value / 255f;
                    while (vertex < edge - 2 && vertices[offset + vertex + 1] < point) vertex++;
                    float start = vertices[offset + vertex], end = vertices[offset + vertex + 1];
                    lower[coordinate] = vertex;
                    // AdaInt uses the lower adjacent interval and adds epsilon to its width.
                    fractions[coordinate] = (point - start) / (end - start + 1e-10f);
                    continue;
                }
                float position = value * (edge - 1) / 255f;
                if (curves != null) {
                    int left = Math.min(edge - 2, (int) position);
                    position = clamp(mix(curves[offset + left], curves[offset + left + 1], position - left)) * (edge - 1);
                }
                lower[coordinate] = Math.min(edge - 2, (int) position);
                fractions[coordinate] = position - lower[coordinate];
            }
        }
    }

    int pixel(int argb, float strength) {
        int r = (argb >>> 16) & 255, g = (argb >>> 8) & 255, b = argb & 255;
        int ri = lower[r], gi = lower[256 + g], bi = lower[512 + b];
        float dx = fractions[r], dy = fractions[256 + g], dz = fractions[512 + b];
        int argbOut = argb & 0xff000000;
        int offset = bi * slice + gi * edge + ri;
        for (int channel = 0; channel < 3; channel++, offset += volume) {
            float low = mix(mix(cube[offset], cube[offset + 1], dx),
                    mix(cube[offset + edge], cube[offset + edge + 1], dx), dy);
            int highAt = offset + slice;
            float high = mix(mix(cube[highAt], cube[highAt + 1], dx),
                    mix(cube[highAt + edge], cube[highAt + edge + 1], dx), dy);
            int before = channel == 0 ? r : channel == 1 ? g : b;
            int after = Math.round(mix(before, clamp(mix(low, high, dz)) * 255f, strength));
            argbOut |= after << ((2 - channel) * 8);
        }
        return argbOut;
    }

    private static void validateVertices(float[] vertices, int offset, int edge) {
        float first = vertices[offset], last = vertices[offset + edge - 1];
        if (first != 0 || !Float.isFinite(last) || Math.abs(last - 1) > 1e-5f) {
            throw new IllegalArgumentException("采样节点无效");
        }
        for (int node = offset + 1; node < offset + edge; node++) {
            if (!Float.isFinite(vertices[node]) || vertices[node] < vertices[node - 1]) {
                throw new IllegalArgumentException("采样节点无效");
            }
        }
    }

    private static float mix(float from, float to, float amount) { return from + amount * (to - from); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
