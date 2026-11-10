// The look of 2000s CCD compact cameras on a developed photo: a port of the filter in Marcs
// Digicam Emulator by Marc Gronenberg (MIT License; github.com/marc-gronenberg/marcs-digicam-emulator,
// plugin/src/lib.rs and common/src/blur.rs), its stages and constants unchanged. See
// engine/Digicam.kt for the stages; this is the per-pixel work, in bands of camera rows (with an
// overlap, so the spatial stages meet without seams) on a few threads.
//
// Copyright (c) 2026 Marc Gronenberg (the emulator); port for Photo All.
#include <jni.h>
#include <android/bitmap.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <random>
#include <thread>
#include <vector>

namespace {

constexpr int N = 37;
constexpr float STOPS_LO = -8.0f, STOPS_HI = 5.0f;
constexpr float READ_NOISE = 10.0f;
constexpr int NT = 1024;
constexpr int DR = 3, DG = N * 3, DB = N * N * 3;
constexpr int BAND = 192;
constexpr int SH_E0 = -11, SH_LEN = 14 * 256;
constexpr int TAB = 4096;

struct Camera {
    int channels;
    float s[4][3];   // linear scene -> raw (fraction of full well)
    float a[3][4];   // raw noise -> linear scene
    float megapixels, fullWell, sharpen, sharpenRadius, nr, bloom, fringe;
};

struct Tables {
    float sh[SH_LEN + 1];
    float dec8[256];
    float decTab[TAB + 1];
    float encTab[TAB + 1];
    std::vector<int8_t> noise;
    Tables() : noise(size_t(NT) * NT * 4) {
        const float log2_018 = std::log2(0.18f);
        for (int k = 0; k <= SH_LEN; ++k) {
            const double e = SH_E0 + k / 256;
            const double m = 1.0 + (k % 256) / 256.0;
            sh[k] = float(((e + std::log2(m)) - log2_018 - STOPS_LO) / (STOPS_HI - STOPS_LO) * (N - 1));
        }
        auto dec = [](double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); };
        for (int i = 0; i < 256; ++i) dec8[i] = float(dec(i / 255.0));
        for (int i = 0; i <= TAB; ++i) decTab[i] = float(dec(double(i) / TAB));
        for (int i = 0; i <= TAB; ++i) {
            const double x = (double(i) / TAB) * (double(i) / TAB); // indexed by sqrt(linear)
            encTab[i] = float(x <= 0.0031308 ? x * 12.92 : 1.055 * std::pow(x, 1 / 2.4) - 0.055);
        }
        // white Gaussian noise, unit spread 32, as the emulator's tile
        std::mt19937 rng(0x5eed1e);
        std::normal_distribution<float> g(0.0f, 32.0f);
        for (auto &v : noise) v = int8_t(std::clamp(int(std::lround(g(rng))), -127, 127));
    }
};

const Tables &tables() {
    static Tables t;
    return t;
}

inline float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

inline float decode(const Tables &t, float v) {
    const float p = clamp01(v) * TAB;
    const int i = std::min(int(p), TAB - 1);
    return t.decTab[i] + (t.decTab[i + 1] - t.decTab[i]) * (p - i);
}

inline float encode(const Tables &t, float v) {
    const float p = std::sqrt(clamp01(v)) * TAB;
    const int i = std::min(int(p), TAB - 1);
    return t.encTab[i] + (t.encTab[i + 1] - t.encTab[i]) * (p - i);
}

inline float shaper(const Tables &t, float v) {
    v = v > 1e-30f ? v : 1e-30f;
    uint32_t bits;
    std::memcpy(&bits, &v, 4);
    const int k = int(bits >> 15) - ((127 + SH_E0) << 8);
    float p;
    if (k < 0) p = 0;
    else if (k >= SH_LEN) p = N - 1;
    else {
        const float f = (bits & 0x7fff) * (1.0f / 32768.0f);
        p = t.sh[k] + (t.sh[k + 1] - t.sh[k]) * f;
    }
    return std::clamp(p, 0.0f, float(N - 1));
}

inline void render(const Tables &t, const float *lut, float r, float g, float b, float out[3]) {
    const float pr = shaper(t, r), pg = shaper(t, g), pb = shaper(t, b);
    const int ir = std::min(int(pr), N - 2), ig = std::min(int(pg), N - 2), ib = std::min(int(pb), N - 2);
    const float fr = pr - ir, fg = pg - ig, fb = pb - ib;
    int a, bb;
    float w1, w2, w3;
    if (fr > fg) {
        if (fg > fb) { a = DR; bb = DR + DG; w1 = fr - fg; w2 = fg - fb; w3 = fb; }
        else if (fr > fb) { a = DR; bb = DR + DB; w1 = fr - fb; w2 = fb - fg; w3 = fg; }
        else { a = DB; bb = DR + DB; w1 = fb - fr; w2 = fr - fg; w3 = fg; }
    } else if (fb > fg) { a = DB; bb = DG + DB; w1 = fb - fg; w2 = fg - fr; w3 = fr; }
    else if (fb > fr) { a = DG; bb = DG + DB; w1 = fg - fb; w2 = fb - fr; w3 = fr; }
    else { a = DG; bb = DR + DG; w1 = fg - fr; w2 = fr - fb; w3 = fb; }
    const float w0 = 1.0f - (w1 + w2 + w3);
    const float *p0 = lut + ib * DB + ig * DG + ir * DR;
    const float *pa = p0 + a, *pb2 = p0 + bb, *pc = p0 + DR + DG + DB;
    for (int k = 0; k < 3; ++k) out[k] = w0 * p0[k] + w1 * pa[k] + w2 * pb2[k] + w3 * pc[k];
}

// ---- blurs (common/src/blur.rs): two box passes each way, edges repeat ----------------------------

void boxPass(const float *src, float *dst, int lines, int len, int lineStep, int stride, int r) {
    const float inv = 1.0f / float(2 * r + 1);
    const int last = len - 1;
    for (int l = 0; l < lines; ++l) {
        const float *s = src + size_t(l) * lineStep;
        float *d = dst + size_t(l) * lineStep;
        float acc = 0;
        for (int i = 0; i <= 2 * r; ++i) {
            const int j = i < r ? 0 : (i - r > last ? last : i - r);
            acc += s[size_t(j) * stride];
        }
        for (int i = 0; i < len; ++i) {
            d[size_t(i) * stride] = acc * inv;
            const float next = i + r < last ? s[size_t(i + r + 1) * stride] : s[size_t(last) * stride];
            const float prev = i >= r ? s[size_t(i - r) * stride] : s[0];
            acc += next - prev;
        }
    }
}

void blurRect(float *a, float *tmp, int pitch, int w, int h, int r) {
    if (r == 0 || w <= 0 || h <= 0) return;
    for (int it = 0; it < 2; ++it) {
        boxPass(a, tmp, h, w, pitch, 1, r);
        boxPass(tmp, a, w, h, 1, pitch, r);
    }
}

// chroma averaged over 2 x 2 blocks, blurred with radius r there, interpolated back
void chromaHalf(float *cb, float *cr, float *hb, float *hr, float *tmp, int w, int h, int r) {
    const int hw = (w + 1) / 2, hh = (h + 1) / 2;
    for (int y = 0; y < hh; ++y) {
        const int y0 = 2 * y, y1 = std::min(2 * y + 1, h - 1);
        for (int x = 0; x < hw; ++x) {
            const int x0 = 2 * x, x1 = std::min(2 * x + 1, w - 1);
            const size_t a = size_t(y0) * w + x0, b = size_t(y0) * w + x1, c = size_t(y1) * w + x0, d = size_t(y1) * w + x1;
            hb[size_t(y) * hw + x] = (cb[a] + cb[b] + cb[c] + cb[d]) * 0.25f;
            hr[size_t(y) * hw + x] = (cr[a] + cr[b] + cr[c] + cr[d]) * 0.25f;
        }
    }
    blurRect(hb, tmp, hw, hw, hh, r);
    blurRect(hr, tmp, hw, hw, hh, r);
    for (int y = 0; y < h; ++y) {
        const float fy = std::max(0.0f, (y - 0.5f) * 0.5f);
        int iy = int(fy), jy;
        float ty;
        if (iy + 1 < hh) { jy = iy + 1; ty = fy - iy; } else { iy = jy = hh - 1; ty = 0; }
        for (int x = 0; x < w; ++x) {
            const float fx = std::max(0.0f, (x - 0.5f) * 0.5f);
            int ix = int(fx), jx;
            float tx;
            if (ix + 1 < hw) { jx = ix + 1; tx = fx - ix; } else { ix = jx = hw - 1; tx = 0; }
            const size_t i00 = size_t(iy) * hw + ix, i01 = size_t(iy) * hw + jx, i10 = size_t(jy) * hw + ix, i11 = size_t(jy) * hw + jx;
            float t = hb[i00] + (hb[i01] - hb[i00]) * tx, bt = hb[i10] + (hb[i11] - hb[i10]) * tx;
            cb[size_t(y) * w + x] = t + (bt - t) * ty;
            t = hr[i00] + (hr[i01] - hr[i00]) * tx; bt = hr[i10] + (hr[i11] - hr[i10]) * tx;
            cr[size_t(y) * w + x] = t + (bt - t) * ty;
        }
    }
}

uint32_t mixHash(uint32_t s) {
    uint32_t h = s * 0x9e3779b9u ^ 0x85ebca6bu;
    h ^= h >> 13;
    h *= 0xc2b2ae35u;
    return h ^ (h >> 16);
}

struct Job {
    const uint8_t *src; uint8_t *dst; int W, H, srcStride, dstStride;
    const Camera *cam; const float *lut;
    int f, cw, ch, ov, ox, oy;
    float invFw, readVar, nrAmt, strength;
};

// one band of camera rows [c0, c1) with its overlap; `buf` the thread's planes
void band(const Job &j, int c0, std::vector<float> &buf) {
    const Tables &t = tables();
    const Camera &cam = *j.cam;
    const int f = j.f, W = j.W, H = j.H;
    const int c1 = std::min(j.ch, c0 + BAND);
    const int a0 = std::max(0, c0 - j.ov), a1 = std::min(j.ch, c1 + j.ov);
    const int pw = j.cw, ph = a1 - a0;
    const size_t area = size_t(pw) * ph;
    const size_t half = size_t((pw + 1) / 2) * ((ph + 1) / 2);
    const size_t need = area * 10 + half * 3 + size_t(2 * (pw + ph) + 8) * 2;
    if (buf.size() < need) buf.resize(need);
    float *inR = buf.data(), *inG = inR + area, *inB = inG + area, *inC = inB + area, *cnt = inC + area;
    float *py = cnt + area, *pcb = py + area, *pcr = pcb + area, *blown = pcr + area;
    float *s1 = blown + area, *s2 = s1 + area + 2 * (pw + ph) + 8;
    float *hb = s2 + area + 2 * (pw + ph) + 8, *hr = hb + half, *ht = hr + half;
    // ---- 0: camera pixels, the mean of encoded values per f x f block (decoded once)
    std::fill(inR, inR + area * 5, 0.0f);
    const int docY0 = a0 * f, docY1 = std::min(H, a1 * f);
    for (int dy = docY0; dy < docY1; ++dy) {
        const uint8_t *row = j.src + size_t(dy) * j.srcStride;
        const size_t l0 = size_t((dy - docY0) / f) * pw;
        for (int dx = 0; dx < W; ++dx) {
            const uint8_t *p = row + size_t(dx) * 4;
            const size_t l = l0 + dx / f;
            inR[l] += p[0] * (1.0f / 255); inG[l] += p[1] * (1.0f / 255); inB[l] += p[2] * (1.0f / 255);
            if (p[0] == 255 || p[1] == 255 || p[2] == 255) inC[l] += 1;
            cnt[l] += 1;
        }
    }
    for (size_t i = 0; i < area; ++i) {
        const float inv = 1.0f / std::max(cnt[i], 1.0f);
        inR[i] = decode(t, inR[i] * inv); inG[i] = decode(t, inG[i] * inv); inB[i] = decode(t, inB[i] * inv); inC[i] *= inv;
    }
    // ---- 1-2: noise, the camera, Y'CbCr; the blown-highlight map
    const bool k4 = cam.channels == 4;
    std::vector<uint8_t> cols(pw, 0);
    int bx0 = INT32_MAX, bx1 = -1, by0 = INT32_MAX, by1 = -1;
    float rgb[3];
    for (int y = 0; y < ph; ++y) {
        const int8_t *nrow = t.noise.data() + size_t((a0 + y + j.oy) & (NT - 1)) * NT * 4;
        for (int x = 0; x < pw; ++x) {
            const size_t i = size_t(y) * pw + x;
            float r = inR[i], g = inG[i], b = inB[i];
            const float r0 = cam.s[0][0] * r + cam.s[0][1] * g + cam.s[0][2] * b;
            const float r1 = cam.s[1][0] * r + cam.s[1][1] * g + cam.s[1][2] * b;
            const float r2 = cam.s[2][0] * r + cam.s[2][1] * g + cam.s[2][2] * b;
            const float r3 = k4 ? cam.s[3][0] * r + cam.s[3][1] * g + cam.s[3][2] * b : 0.0f;
            float top = std::max(r0, std::max(r1, r2));
            if (k4 && r3 > top) top = r3;
            if (inC[i] >= 0.5f && 2.0f > top) top = 2.0f; // clipped in the 8-bit photo: one stop over
            if (top > 1.0f) {
                blown[i] = std::log2(top); cols[x] = 1;
                bx0 = std::min(bx0, x); bx1 = std::max(bx1, x); by0 = std::min(by0, y); by1 = y;
            } else blown[i] = 0;
            const int8_t *q = nrow + size_t((x + j.ox) & (NT - 1)) * 4;
            const float d0 = q[0] * std::sqrt(clamp01(r0) * j.invFw + j.readVar) * (1.0f / 32);
            const float d1 = q[1] * std::sqrt(clamp01(r1) * j.invFw + j.readVar) * (1.0f / 32);
            const float d2 = q[2] * std::sqrt(clamp01(r2) * j.invFw + j.readVar) * (1.0f / 32);
            const float d3 = k4 ? q[3] * std::sqrt(clamp01(r3) * j.invFw + j.readVar) * (1.0f / 32) : 0.0f;
            r += cam.a[0][0] * d0 + cam.a[0][1] * d1 + cam.a[0][2] * d2 + cam.a[0][3] * d3;
            g += cam.a[1][0] * d0 + cam.a[1][1] * d1 + cam.a[1][2] * d2 + cam.a[1][3] * d3;
            b += cam.a[2][0] * d0 + cam.a[2][1] * d1 + cam.a[2][2] * d2 + cam.a[2][3] * d3;
            render(t, j.lut, std::max(r, 0.0f), std::max(g, 0.0f), std::max(b, 0.0f), rgb);
            const float yy = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2];
            py[i] = yy; pcb[i] = rgb[2] - yy; pcr[i] = rgb[0] - yy;
        }
    }
    if (by1 >= 0) {
        // ---- 3: blooming along the columns with a blown pixel
        if (cam.bloom > 0) {
            const float reachDoc = std::min(1.5f + 1.5f * cam.bloom, 3.5f);
            const float reach = std::max(reachDoc / f, 1.0f);
            const float gain = 0.16f * cam.bloom * reachDoc / reach;
            const float keep = 1.0f - 1.0f / reach;
            for (int x = bx0; x <= bx1; ++x) {
                if (!cols[x]) continue;
                float acc = 0;
                for (int y = 0; y < ph; ++y) { acc = acc * keep + blown[size_t(y) * pw + x]; s1[size_t(y) * pw + x] = acc; }
                acc = 0;
                for (int y = ph - 1; y >= 0; --y) {
                    const size_t i = size_t(y) * pw + x;
                    const float e = blown[i];
                    acc = acc * keep + e;
                    const float st = std::min((s1[i] + acc - e) * gain, 1.0f);
                    py[i] += (1.0f - py[i]) * st; pcb[i] *= 1.0f - st; pcr[i] *= 1.0f - st;
                }
            }
        }
        // ---- 4: purple fringing round the blown pixels
        if (cam.fringe > 0) {
            const int mg = 6;
            const int xa = std::max(0, bx0 - mg), xb = std::min(pw - 1, bx1 + mg);
            const int ya = std::max(0, by0 - mg), yb = std::min(ph - 1, by1 + mg);
            for (int y = ya; y <= yb; ++y) for (int x = xa; x <= xb; ++x) s1[size_t(y) * pw + x] = std::min(blown[size_t(y) * pw + x], 1.0f);
            blurRect(s1 + size_t(ya) * pw + xa, s2 + size_t(ya) * pw + xa, pw, xb - xa + 1, yb - ya + 1, f == 1 ? 2 : 1);
            const float FR = 0.45f, FG = -0.08f, FB = 0.85f, FY = 0.299f * FR + 0.587f * FG + 0.114f * FB;
            for (int y = ya; y <= yb; ++y) for (int x = xa; x <= xb; ++x) {
                const size_t i = size_t(y) * pw + x;
                const float edge = s1[i] - std::min(blown[i], 1.0f);
                if (edge > 0) {
                    const float k = edge * cam.fringe * 0.9f;
                    py[i] += k * FY; pcb[i] += k * (FB - FY); pcr[i] += k * (FR - FY);
                }
            }
        }
    }
    // ---- 5: noise reduction and sharpening
    int rc = std::min(int(j.nrAmt * 1.25f + 0.5f), 6);
    if (f > 1) rc = int(float(rc) / f + 0.5f);
    rc = std::min(rc, std::max(0, j.ov - 4) / 4);
    chromaHalf(pcb, pcr, hb, hr, ht, pw, ph, rc);
    const float wl = std::clamp((j.nrAmt - 0.6f) * 0.35f, 0.0f, 0.75f);
    if (wl > 0 || cam.sharpen > 0) {
        const int rs = std::max(1, int(cam.sharpenRadius + 0.5f));
        std::memcpy(s1, py, area * sizeof(float));
        blurRect(s1, s2, pw, pw, ph, rs);
        const float k = cam.sharpen * 1.6f;
        for (size_t i = 0; i < area; ++i) {
            const float bl = s1[i];
            const float yv = py[i] + (bl - py[i]) * wl;
            float d = yv - bl;
            d = d > 0.004f ? d - 0.004f : (d < -0.004f ? d + 0.004f : 0.0f);
            py[i] = yv + d * k;
        }
    }
    for (size_t i = 0; i < area; ++i) { // to encoded R, G, B
        const float yy = py[i], er = yy + pcr[i], eb = yy + pcb[i];
        py[i] = er; pcb[i] = (yy - 0.299f * er - 0.114f * eb) / 0.587f; pcr[i] = eb;
    }
    // ---- 6: back to the photo's pixels for this band's own rows, mixed by strength
    const int coreY0 = c0 * f, coreY1 = std::min(H, c1 * f);
    const float invF = 1.0f / f, str = j.strength;
    for (int dy = coreY0; dy < coreY1; ++dy) {
        const float u = std::max(0.0f, (dy + 0.5f) * invF - 0.5f - a0);
        int iy = int(u), jy;
        float ty;
        if (iy + 1 < ph) { jy = iy + 1; ty = u - iy; } else { iy = jy = ph - 1; ty = 0; }
        const uint8_t *srow = j.src + size_t(dy) * j.srcStride;
        uint8_t *drow = j.dst + size_t(dy) * j.dstStride;
        for (int dx = 0; dx < W; ++dx) {
            const float v = std::max(0.0f, (dx + 0.5f) * invF - 0.5f);
            int ix = int(v), jx;
            float tx;
            if (ix + 1 < pw) { jx = ix + 1; tx = v - ix; } else { ix = jx = pw - 1; tx = 0; }
            const size_t i00 = size_t(iy) * pw + ix, i01 = size_t(iy) * pw + jx, i10 = size_t(jy) * pw + ix, i11 = size_t(jy) * pw + jx;
            float c[3];
            const float *pl[3] = {py, pcb, pcr};
            for (int k = 0; k < 3; ++k) {
                const float tt = pl[k][i00] + (pl[k][i01] - pl[k][i00]) * tx, bt = pl[k][i10] + (pl[k][i11] - pl[k][i10]) * tx;
                c[k] = tt + (bt - tt) * ty;
            }
            const uint8_t *o = srow + size_t(dx) * 4;
            uint8_t *d = drow + size_t(dx) * 4;
            for (int k = 0; k < 3; ++k) {
                float e = c[k];
                if (str < 1.0f) {
                    const float lo = t.dec8[o[k]];
                    e = encode(t, lo + (decode(t, e) - lo) * str);
                }
                d[k] = uint8_t(clamp01(e) * 255.0f + 0.5f);
            }
            d[3] = 255;
        }
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_photoall_film_engine_FilmNative_digicam(JNIEnv *env, jclass, jobject srcBitmap, jobject dstBitmap,
                                                 jbyteArray lutU16, jfloatArray params, jint iso, jfloat strength, jint seed) {
    AndroidBitmapInfo si{}, di{};
    if (AndroidBitmap_getInfo(env, srcBitmap, &si) != 0 || AndroidBitmap_getInfo(env, dstBitmap, &di) != 0) return JNI_FALSE;
    if (si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || di.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        si.width != di.width || si.height != di.height) return JNI_FALSE;
    // params: channels, s[4][3], a[3][4], megapixels, fullWell, sharpen, sharpenRadius, nr, bloom, fringe
    if (env->GetArrayLength(params) < 1 + 12 + 12 + 7) return JNI_FALSE;
    std::vector<float> pv(env->GetArrayLength(params));
    env->GetFloatArrayRegion(params, 0, jsize(pv.size()), pv.data());
    Camera cam{};
    cam.channels = int(pv[0]);
    for (int r = 0; r < 4; ++r) for (int c = 0; c < 3; ++c) cam.s[r][c] = pv[1 + r * 3 + c];
    for (int r = 0; r < 3; ++r) for (int c = 0; c < 4; ++c) cam.a[r][c] = pv[13 + r * 4 + c];
    cam.megapixels = pv[25]; cam.fullWell = pv[26]; cam.sharpen = pv[27]; cam.sharpenRadius = pv[28];
    cam.nr = pv[29]; cam.bloom = pv[30]; cam.fringe = pv[31];
    const jsize lutBytes = env->GetArrayLength(lutU16);
    if (lutBytes != N * N * N * 3 * 2) return JNI_FALSE;
    std::vector<float> lut(N * N * N * 3);
    {
        std::vector<uint8_t> raw(lutBytes);
        env->GetByteArrayRegion(lutU16, 0, lutBytes, reinterpret_cast<jbyte *>(raw.data()));
        for (size_t i = 0; i < lut.size(); ++i) lut[i] = float(raw[2 * i] | (raw[2 * i + 1] << 8)) * (1.0f / 65535.0f);
    }
    void *sp = nullptr, *dp = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &sp) != 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dp) != 0) { AndroidBitmap_unlockPixels(env, srcBitmap); return JNI_FALSE; }

    Job j{};
    j.src = static_cast<const uint8_t *>(sp); j.dst = static_cast<uint8_t *>(dp);
    j.W = int(si.width); j.H = int(si.height); j.srcStride = int(si.stride); j.dstStride = int(di.stride);
    j.cam = &cam; j.lut = lut.data();
    const float isoGain = std::clamp(iso / 100.0f, 1.0f, 16.0f);
    j.f = std::clamp(int(std::sqrt(float(j.W) * j.H / (cam.megapixels * 1e6f)) + 0.5f), 1, 4);
    j.cw = (j.W + j.f - 1) / j.f; j.ch = (j.H + j.f - 1) / j.f;
    j.ov = (int(std::ceil(16.0f / j.f)) + 1) / 2 * 2;
    j.ox = int(mixHash(uint32_t(seed)) % NT); j.oy = int(mixHash(uint32_t(seed) ^ 0x5bd1e995u) % NT);
    j.invFw = isoGain / cam.fullWell;
    const float pre = READ_NOISE * 0.25f, post = READ_NOISE / isoGain;
    const float sigma = std::sqrt(pre * pre + post * post) * isoGain / cam.fullWell;
    j.readVar = sigma * sigma;
    j.nrAmt = cam.nr * std::sqrt(isoGain);
    j.strength = std::clamp(strength, 0.0f, 1.0f);
    tables();
    // bands on two threads (each keeps its planes), next band by a shared counter
    std::atomic<int> next{0};
    const int bands = (j.ch + BAND - 1) / BAND;
    auto worker = [&]() {
        std::vector<float> buf;
        for (int b; (b = next.fetch_add(1)) < bands;) band(j, b * BAND, buf);
    };
    const int threads = std::max(1, std::min(2, int(std::thread::hardware_concurrency())));
    std::vector<std::thread> pool;
    for (int i = 1; i < threads; ++i) pool.emplace_back(worker);
    worker();
    for (auto &th : pool) th.join();
    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
}
