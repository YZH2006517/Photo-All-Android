// JNI bridge for org.photoall.film.engine.FilmNative.
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "film_engine.h"
#include "jni_guard.h"

namespace {

struct Handle
{
  std::unique_ptr<film::FilmEngine> engine;
  film::DevelopResult last;
  std::mutex mutex;
};

void throw_java(JNIEnv *env, const std::string &message)
{
  jclass type = env->FindClass("java/lang/IllegalStateException");
  env->ThrowNew(type, message.c_str());
}

bool read_asset(AAssetManager *assets, const char *path, std::vector<uint8_t> &bytes)
{
  AAsset *asset = AAssetManager_open(assets, path, AASSET_MODE_BUFFER);
  if(!asset) return false;
  const off_t size = AAsset_getLength(asset);
  bytes.resize(static_cast<size_t>(size));
  const bool ok = AAsset_read(asset, bytes.data(), bytes.size()) == size;
  AAsset_close(asset);
  return ok;
}

bool load_lut(AAssetManager *assets, const char *path, film::Lut &lut, std::string &error)
{
  std::vector<uint8_t> bytes;
  if(!read_asset(assets, path, bytes))
  {
    error = std::string("missing asset ") + path;
    return false;
  }
  return film::parse_lut(bytes.data(), bytes.size(), lut, error);
}

Handle *handle_of(jlong value) { return reinterpret_cast<Handle *>(value); }

// Applies parallel name/value arrays ("stage film" / "stage print" go to [stages]);
// throws and returns false on an unknown name.
bool read_params(JNIEnv *env, jobjectArray names, jfloatArray values, film::FilmParams &params,
                 film::Stages &stages)
{
  const jsize count = env->GetArrayLength(names);
  jfloat *v = env->GetFloatArrayElements(values, nullptr);
  bool ok = true;
  for(jsize i = 0; ok && i < count; i++)
  {
    auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    const char *chars = env->GetStringUTFChars(name, nullptr);
    ok = film::set_stage(stages, chars, v[i]) || film::set_param(params, chars, v[i]);
    const std::string copy = chars;
    env->ReleaseStringUTFChars(name, chars);
    env->DeleteLocalRef(name);
    if(!ok) throw_java(env, "unknown film parameter: " + copy);
  }
  env->ReleaseFloatArrayElements(values, v, JNI_ABORT);
  return ok;
}

} // namespace

extern "C" {

// Native byte buffers for DNGs written in memory: outside the Java heap (a burst is
// ~25 MB a frame), freed explicitly.
JNIEXPORT jlong JNICALL Java_org_photoall_film_engine_FilmNative_bufferCreate(JNIEnv *env, jclass, jlong capacity)
{
  JNI_TRY
  auto *b = new std::vector<uint8_t>();
  b->reserve(static_cast<size_t>(capacity));
  return reinterpret_cast<jlong>(b);
  JNI_CATCH(env)
  return 0;
}

JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_bufferWrite(JNIEnv *env, jclass, jlong handle,
                                                                             jbyteArray data, jint offset, jint length)
{
  JNI_TRY
  auto *b = reinterpret_cast<std::vector<uint8_t> *>(handle);
  const size_t at = b->size();
  b->resize(at + static_cast<size_t>(length));
  env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte *>(b->data() + at));
  JNI_CATCH(env)
  return;
}

JNIEXPORT jboolean JNICALL Java_org_photoall_film_engine_FilmNative_bufferSave(JNIEnv *env, jclass, jlong handle,
                                                                                jstring path)
{
  JNI_TRY
  auto *b = reinterpret_cast<std::vector<uint8_t> *>(handle);
  const char *chars = env->GetStringUTFChars(path, nullptr);
  FILE *f = std::fopen(chars, "wb");
  env->ReleaseStringUTFChars(path, chars);
  if(!f) return JNI_FALSE;
  const bool ok = std::fwrite(b->data(), 1, b->size(), f) == b->size();
  std::fclose(f);
  return ok ? JNI_TRUE : JNI_FALSE;
  JNI_CATCH(env)
  return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_bufferRelease(JNIEnv *env, jclass, jlong handle)
{
  JNI_TRY
  delete reinterpret_cast<std::vector<uint8_t> *>(handle);
  JNI_CATCH(env)
  return;
}

JNIEXPORT jlong JNICALL Java_org_photoall_film_engine_FilmNative_create(JNIEnv *env, jclass, jobject asset_manager)
{
  JNI_TRY
  AAssetManager *assets = AAssetManager_fromJava(env, asset_manager);
  film::Lut filmsim, spectra;
  std::string error;
  if(!load_lut(assets, "filmsim/filmsim.lut", filmsim, error) ||
     !load_lut(assets, "filmsim/spectra-em.lut", spectra, error))
  {
    throw_java(env, error);
    return 0;
  }
  auto handle = std::make_unique<Handle>();
  handle->engine = film::FilmEngine::create(filmsim, spectra, error);
  if(!handle->engine)
  {
    throw_java(env, error);
    return 0;
  }
  return reinterpret_cast<jlong>(handle.release());
  JNI_CATCH(env)
  return 0;
}

JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_destroy(JNIEnv *env, jclass, jlong handle)
{
  JNI_TRY
  delete handle_of(handle);
  JNI_CATCH(env)
  return;
}

JNIEXPORT jstring JNICALL Java_org_photoall_film_engine_FilmNative_deviceName(JNIEnv *env, jclass, jlong handle)
{
  JNI_TRY
  return env->NewStringUTF(handle_of(handle)->engine->device().name.c_str());
  JNI_CATCH(env)
  return nullptr;
}

JNIEXPORT jobjectArray JNICALL Java_org_photoall_film_engine_FilmNative_names(JNIEnv *env, jclass, jboolean papers)
{
  JNI_TRY
  const char *const *names = papers ? film::kPapers : film::kFilmStocks;
  const int count = papers ? film::kPaperCount : film::kFilmCount;
  jobjectArray out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
  for(int i = 0; i < count; i++) env->SetObjectArrayElement(out, i, env->NewStringUTF(names[i]));
  return out;
  JNI_CATCH(env)
  return nullptr;
}

// Returns {width, height, passes, peak image bytes, gpu microseconds}; pixels stay
// in the handle until copyResult().
JNIEXPORT jlongArray JNICALL Java_org_photoall_film_engine_FilmNative_develop(
    JNIEnv *env, jclass, jlong handle_value, jobject bitmap, jboolean source_p3, jobjectArray names,
    jfloatArray values, jint max_long_edge, jfloat exposure_ev, jint seed, jboolean output_p3)
{
  JNI_TRY
  Handle *handle = handle_of(handle_value);
  film::FilmParams params;
  film::Stages stages;
  if(!read_params(env, names, values, params, stages)) return nullptr;

  AndroidBitmapInfo info;
  void *pixels = nullptr;
  if(AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
     info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
  {
    throw_java(env, "source must be an ARGB_8888 bitmap");
    return nullptr;
  }
  if(AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
  {
    throw_java(env, "cannot lock source bitmap");
    return nullptr;
  }
  std::vector<uint8_t> packed;
  const uint8_t *rgba = static_cast<const uint8_t *>(pixels);
  if(info.stride != info.width * 4)
  {
    packed.resize(static_cast<size_t>(info.width) * info.height * 4);
    for(uint32_t y = 0; y < info.height; y++)
      std::memcpy(&packed[static_cast<size_t>(y) * info.width * 4], rgba + static_cast<size_t>(y) * info.stride,
                  info.width * 4);
    rgba = packed.data();
  }

  film::SourceImage source;
  source.width = static_cast<int>(info.width);
  source.height = static_cast<int>(info.height);
  source.rgba8 = rgba;
  source.space = source_p3 ? film::ColourSpace::DisplayP3 : film::ColourSpace::Srgb;
  film::DevelopOptions options;
  options.max_long_edge = max_long_edge;
  options.exposure_ev = exposure_ev;
  options.seed = static_cast<uint32_t>(seed);
  options.output = output_p3 ? film::ColourSpace::DisplayP3 : film::ColourSpace::Srgb;
  options.stages = stages;

  std::string error;
  std::lock_guard<std::mutex> lock(handle->mutex);
  const bool ok = handle->engine->develop(source, params, options, handle->last, error);
  AndroidBitmap_unlockPixels(env, bitmap);
  if(!ok)
  {
    throw_java(env, error);
    return nullptr;
  }
  const jlong out[5] = {handle->last.width, handle->last.height, handle->last.passes,
                        static_cast<jlong>(handle->last.peak_image_bytes),
                        static_cast<jlong>(handle->last.gpu_ms * 1000.0)};
  jlongArray result = env->NewLongArray(5);
  env->SetLongArrayRegion(result, 0, 5, out);
  return result;
  JNI_CATCH(env)
  return nullptr;
}

// Develops one DNG, or a burst of DNGs merged HDR+-style (first = reference). Each
// source is a file path (String) or a direct ByteBuffer holding the DNG up to its limit
// (frames written in memory, no file in between). frame_exposure: the frames' relative
// exposures as asked of the camera, or null.
// Returns {width, height, passes, peak bytes, gpu us, TIFF orientation}.
JNIEXPORT jlongArray JNICALL Java_org_photoall_film_engine_FilmNative_developDng(
    JNIEnv *env, jclass, jlong handle_value, jobjectArray paths, jobjectArray names, jfloatArray values,
    jint max_long_edge, jfloat exposure_ev, jint seed, jboolean output_p3, jfloat match_key, jfloat local_tone,
    jfloat chroma_denoise, jfloat raw_denoise,
    jfloatArray neutral_override, jfloat noise_s, jfloat noise_o, jfloatArray flash_mix,
    jfloatArray flash_white, jfloatArray frame_exposure)
{
  JNI_TRY
  Handle *handle = handle_of(handle_value);
  film::FilmParams params;
  film::Stages stages;
  if(!read_params(env, names, values, params, stages)) return nullptr;

  // The camera's own neutral and noise model at the shot (CameraX writes stale ones).
  float neutral[3] = {0, 0, 0};
  const bool has_neutral = neutral_override && env->GetArrayLength(neutral_override) == 3;
  if(has_neutral) env->GetFloatArrayRegion(neutral_override, 0, 3, neutral);
  const jsize count = env->GetArrayLength(paths);
  const auto parse_start = std::chrono::steady_clock::now();
  std::vector<film::RawImage> raws(static_cast<size_t>(count));
  std::string error;
  jclass string_class = env->FindClass("java/lang/String");
  jclass long_class = env->FindClass("java/lang/Long");
  jmethodID long_value = env->GetMethodID(long_class, "longValue", "()J");
  jclass buffer_class = env->FindClass("java/nio/Buffer");
  jmethodID limit = env->GetMethodID(buffer_class, "limit", "()I");
  // Where each DNG is (a file read into memory, a native buffer or a direct ByteBuffer),
  // then the frames parsed on several threads at once.
  std::vector<std::vector<uint8_t>> files(static_cast<size_t>(count));
  std::vector<const uint8_t *> datas(static_cast<size_t>(count), nullptr);
  std::vector<size_t> sizes(static_cast<size_t>(count), 0);
  for(jsize i = 0; i < count && error.empty(); i++)
  {
    jobject item = env->GetObjectArrayElement(paths, i);
    if(env->IsInstanceOf(item, string_class))
    {
      auto path = static_cast<jstring>(item);
      const char *chars = env->GetStringUTFChars(path, nullptr);
      const std::string file = chars;
      env->ReleaseStringUTFChars(path, chars);
      std::vector<uint8_t> &bytes = files[i];
      if(FILE *f = std::fopen(file.c_str(), "rb"))
      {
        std::fseek(f, 0, SEEK_END);
        bytes.resize(static_cast<size_t>(std::ftell(f)));
        std::fseek(f, 0, SEEK_SET);
        if(std::fread(bytes.data(), 1, bytes.size(), f) != bytes.size()) bytes.clear();
        std::fclose(f);
      }
      if(bytes.empty()) error = "cannot read " + file;
      datas[i] = bytes.data();
      sizes[i] = bytes.size();
    }
    else if(env->IsInstanceOf(item, long_class))
    { // a native buffer (bufferCreate)
      auto *b = reinterpret_cast<std::vector<uint8_t> *>(env->CallLongMethod(item, long_value));
      datas[i] = b ? b->data() : nullptr;
      sizes[i] = b ? b->size() : 0;
      if(!datas[i] || sizes[i] == 0) error = "empty DNG buffer";
    }
    else
    {
      datas[i] = static_cast<const uint8_t *>(env->GetDirectBufferAddress(item));
      sizes[i] = static_cast<size_t>(env->CallIntMethod(item, limit));
      if(!datas[i] || sizes[i] == 0) error = "DNG buffer is not a direct ByteBuffer";
    }
    env->DeleteLocalRef(item);
  }
  if(error.empty())
  {
    std::vector<std::string> errors(static_cast<size_t>(count));
    std::vector<std::thread> workers;
    for(jsize i = 0; i < count; i++)
      workers.emplace_back([&, i] {
        // an error escaping a std::thread ends the process: it becomes this frame's error
        try
        {
          if(film::parse_dng(datas[i], sizes[i], raws[i], errors[i], has_neutral ? neutral : nullptr) && noise_s > 0.0f)
          {
            raws[i].noise_scale = noise_s;
            raws[i].noise_offset = noise_o;
          }
        }
        catch(const std::bad_alloc &)
        {
          errors[i] = "out of memory reading DNG " + std::to_string(i);
        }
        catch(const std::exception &e)
        {
          errors[i] = std::string("DNG ") + std::to_string(i) + ": " + e.what();
        }
        catch(...)
        {
          errors[i] = "DNG " + std::to_string(i) + " unreadable";
        }
      });
    for(std::thread &t : workers) t.join();
    for(const std::string &e : errors)
      if(!e.empty() && error.empty()) error = e;
  }
  if(count == 0) error = "no DNG given";
  if(!error.empty())
  {
    throw_java(env, error);
    return nullptr;
  }
  std::vector<const film::RawImage *> burst;
  for(const film::RawImage &r : raws) burst.push_back(&r);
  const film::RawImage &raw = raws[0];

  film::SourceImage source;
  source.burst = burst.data();
  source.burst_count = static_cast<int>(burst.size());
  film::DevelopOptions options;
  options.max_long_edge = max_long_edge;
  options.exposure_ev = exposure_ev;
  options.seed = static_cast<uint32_t>(seed);
  options.output = output_p3 ? film::ColourSpace::DisplayP3 : film::ColourSpace::Srgb;
  options.stages = stages;
  options.match_key = match_key;
  options.local_tone = local_tone;
  options.chroma_denoise = chroma_denoise;
  options.raw_denoise = raw_denoise;
  if(frame_exposure && env->GetArrayLength(frame_exposure) == count)
  {
    options.frame_exposure.resize(static_cast<size_t>(count));
    env->GetFloatArrayRegion(frame_exposure, 0, count, options.frame_exposure.data());
  }
  if(flash_mix && env->GetArrayLength(flash_mix) == 5)
  {
    float f[5];
    env->GetFloatArrayRegion(flash_mix, 0, 5, f);
    options.flash.enabled = true;
    options.flash.ambient = f[0];
    options.flash.target = f[1];
    for(int c = 0; c < 3; c++) options.flash.wb[c] = f[2 + c];
  }
  if(flash_white && env->GetArrayLength(flash_white) == 6)
  {
    float n[6];
    env->GetFloatArrayRegion(flash_white, 0, 6, n);
    options.flash_white.enabled = true;
    for(int c = 0; c < 3; c++)
    {
      options.flash_white.ambient[c] = n[c];
      options.flash_white.flash[c] = n[3 + c];
    }
  }
  // Diagnosis of what the DNGs carry: pixel brightness (log-average key) against the
  // viewfinder's, and the white balance and noise model taken from each header.
  for(size_t i = 0; i < raws.size(); i++)
  {
    const film::RawImage &r = raws[i];
    const float key = film::raw_key(r);
    __android_log_print(ANDROID_LOG_INFO, "FilmEngine",
                        "dng %zu: key %.5f (viewfinder %.5f, push %+.2f ev) wb %.3f %.3f %.3f noise %.3g %.3g",
                        i, key, match_key, key > 0 && match_key > 0 ? std::log2(match_key / key) : 0.0f, r.wb[0],
                        r.wb[1], r.wb[2], r.noise_scale, r.noise_offset);
  }
  const double parse_ms =
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - parse_start).count();
  std::lock_guard<std::mutex> lock(handle->mutex);
  if(!handle->engine->develop(source, params, options, handle->last, error))
  {
    throw_java(env, error);
    return nullptr;
  }
  char line[96];
  std::snprintf(line, sizeof(line), "冲洗结果\t读取 DNG\t%zu 个，%.0f ms\n", raws.size(), parse_ms);
  handle->last.note += line;
  const jlong out[6] = {handle->last.width, handle->last.height, handle->last.passes,
                        static_cast<jlong>(handle->last.peak_image_bytes),
                        static_cast<jlong>(handle->last.gpu_ms * 1000.0), raw.orientation};
  jlongArray result = env->NewLongArray(6);
  env->SetLongArrayRegion(result, 0, 6, out);
  return result;
  JNI_CATCH(env)
  return nullptr;
}

// Creative LUT after the film: RGB8 size^3, red fastest; null clears it.
JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_setCreativeLut(
    JNIEnv *env, jclass, jlong handle_value, jbyteArray rgb, jint size, jfloat strength)
{
  JNI_TRY
  Handle *handle = handle_of(handle_value);
  std::vector<uint8_t> table;
  if(rgb)
  {
    table.resize(static_cast<size_t>(env->GetArrayLength(rgb)));
    env->GetByteArrayRegion(rgb, 0, static_cast<jsize>(table.size()), reinterpret_cast<jbyte *>(table.data()));
    if(table.size() != static_cast<size_t>(size) * size * size * 3)
    {
      throw_java(env, "creative LUT size mismatch");
      return;
    }
  }
  std::string error;
  std::lock_guard<std::mutex> lock(handle->mutex);
  if(!handle->engine->set_creative_lut(rgb ? table.data() : nullptr, size, strength, error)) throw_java(env, error);
  JNI_CATCH(env)
  return;
}

JNIEXPORT jbyteArray JNICALL Java_org_photoall_film_engine_FilmNative_previewLut(
    JNIEnv *env, jclass, jlong handle_value, jobjectArray names, jfloatArray values, jint size, jboolean output_p3)
{
  JNI_TRY
  Handle *handle = handle_of(handle_value);
  film::FilmParams params;
  film::Stages stages;
  if(!read_params(env, names, values, params, stages)) return nullptr;
  std::vector<uint8_t> rgba;
  std::string error;
  {
    std::lock_guard<std::mutex> lock(handle->mutex);
    if(!handle->engine->bake_preview_lut(params, size,
                                         output_p3 ? film::ColourSpace::DisplayP3 : film::ColourSpace::Srgb, rgba,
                                         error, stages))
    {
      throw_java(env, error);
      return nullptr;
    }
  }
  jbyteArray out = env->NewByteArray(static_cast<jsize>(rgba.size()));
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(rgba.size()), reinterpret_cast<const jbyte *>(rgba.data()));
  return out;
  JNI_CATCH(env)
  return nullptr;
}

// The last develop's record ("section\tkey\tvalue" lines).
JNIEXPORT jstring JNICALL Java_org_photoall_film_engine_FilmNative_lastNote(JNIEnv *env, jclass, jlong handle_value)
{
  JNI_TRY
  Handle *handle = handle_of(handle_value);
  std::lock_guard<std::mutex> lock(handle->mutex);
  return env->NewStringUTF(handle->last.note.c_str());
  JNI_CATCH(env)
  return nullptr;
}

JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_copyResult(JNIEnv *env, jclass, jlong handle_value,
                                                                          jobject bitmap)
{
  JNI_TRY
  Handle *handle = handle_of(handle_value);
  std::lock_guard<std::mutex> lock(handle->mutex);
  const film::DevelopResult &last = handle->last;
  AndroidBitmapInfo info;
  void *pixels = nullptr;
  if(AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
     info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || static_cast<int>(info.width) != last.width ||
     static_cast<int>(info.height) != last.height)
  {
    throw_java(env, "result bitmap must be ARGB_8888 at the developed size");
    return;
  }
  if(AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
  {
    throw_java(env, "cannot lock result bitmap");
    return;
  }
  for(int y = 0; y < last.height; y++)
    std::memcpy(static_cast<uint8_t *>(pixels) + static_cast<size_t>(y) * info.stride,
                &last.rgba8[static_cast<size_t>(y) * last.width * 4], static_cast<size_t>(last.width) * 4);
  AndroidBitmap_unlockPixels(env, bitmap);
  JNI_CATCH(env)
  return;
}

} // extern "C"
