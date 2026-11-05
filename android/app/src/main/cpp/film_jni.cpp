// JNI bridge for org.photoall.film.engine.FilmNative.
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <jni.h>

#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "film_engine.h"

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

JNIEXPORT jlong JNICALL Java_org_photoall_film_engine_FilmNative_create(JNIEnv *env, jclass, jobject asset_manager)
{
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
}

JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_destroy(JNIEnv *, jclass, jlong handle)
{
  delete handle_of(handle);
}

JNIEXPORT jstring JNICALL Java_org_photoall_film_engine_FilmNative_deviceName(JNIEnv *env, jclass, jlong handle)
{
  return env->NewStringUTF(handle_of(handle)->engine->device().name.c_str());
}

JNIEXPORT jobjectArray JNICALL Java_org_photoall_film_engine_FilmNative_names(JNIEnv *env, jclass, jboolean papers)
{
  const char *const *names = papers ? film::kPapers : film::kFilmStocks;
  const int count = papers ? film::kPaperCount : film::kFilmCount;
  jobjectArray out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
  for(int i = 0; i < count; i++) env->SetObjectArrayElement(out, i, env->NewStringUTF(names[i]));
  return out;
}

// Returns {width, height, passes, peak image bytes, gpu microseconds}; pixels stay
// in the handle until copyResult().
JNIEXPORT jlongArray JNICALL Java_org_photoall_film_engine_FilmNative_develop(
    JNIEnv *env, jclass, jlong handle_value, jobject bitmap, jboolean source_p3, jobjectArray names,
    jfloatArray values, jint max_long_edge, jfloat exposure_ev, jint seed, jboolean output_p3)
{
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
}

// Develops one DNG, or a burst of DNGs merged HDR+-style (first = reference).
// Returns {width, height, passes, peak bytes, gpu us, TIFF orientation}.
JNIEXPORT jlongArray JNICALL Java_org_photoall_film_engine_FilmNative_developDng(
    JNIEnv *env, jclass, jlong handle_value, jobjectArray paths, jobjectArray names, jfloatArray values,
    jint max_long_edge, jfloat exposure_ev, jint seed, jboolean output_p3, jfloat match_key)
{
  Handle *handle = handle_of(handle_value);
  film::FilmParams params;
  film::Stages stages;
  if(!read_params(env, names, values, params, stages)) return nullptr;

  const jsize count = env->GetArrayLength(paths);
  std::vector<film::RawImage> raws(static_cast<size_t>(count));
  std::string error;
  for(jsize i = 0; i < count && error.empty(); i++)
  {
    auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
    const char *chars = env->GetStringUTFChars(path, nullptr);
    const std::string file = chars;
    env->ReleaseStringUTFChars(path, chars);
    env->DeleteLocalRef(path);
    std::vector<uint8_t> bytes;
    if(FILE *f = std::fopen(file.c_str(), "rb"))
    {
      std::fseek(f, 0, SEEK_END);
      bytes.resize(static_cast<size_t>(std::ftell(f)));
      std::fseek(f, 0, SEEK_SET);
      if(std::fread(bytes.data(), 1, bytes.size(), f) != bytes.size()) bytes.clear();
      std::fclose(f);
    }
    if(bytes.empty()) error = "cannot read " + file;
    else film::parse_dng(bytes.data(), bytes.size(), raws[i], error);
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
  std::lock_guard<std::mutex> lock(handle->mutex);
  if(!handle->engine->develop(source, params, options, handle->last, error))
  {
    throw_java(env, error);
    return nullptr;
  }
  const jlong out[6] = {handle->last.width, handle->last.height, handle->last.passes,
                        static_cast<jlong>(handle->last.peak_image_bytes),
                        static_cast<jlong>(handle->last.gpu_ms * 1000.0), raw.orientation};
  jlongArray result = env->NewLongArray(6);
  env->SetLongArrayRegion(result, 0, 6, out);
  return result;
}

// Creative LUT after the film: RGB8 size^3, red fastest; null clears it.
JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_setCreativeLut(
    JNIEnv *env, jclass, jlong handle_value, jbyteArray rgb, jint size, jfloat strength)
{
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
}

JNIEXPORT jbyteArray JNICALL Java_org_photoall_film_engine_FilmNative_previewLut(
    JNIEnv *env, jclass, jlong handle_value, jobjectArray names, jfloatArray values, jint size, jboolean output_p3)
{
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
}

JNIEXPORT void JNICALL Java_org_photoall_film_engine_FilmNative_copyResult(JNIEnv *env, jclass, jlong handle_value,
                                                                          jobject bitmap)
{
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
}

} // extern "C"
