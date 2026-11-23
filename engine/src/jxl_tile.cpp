// JPEG XL pieces of a DNG (compression 52546, DNG 1.7: Samsung Expert RAW and others):
// each strip or tile one JPEG XL codestream, decoded with libjxl (BSD-3-Clause) to 16-bit
// samples, the channels interleaved. Without libjxl in the build the decoder says so.
#include "jxl_tile.h"

#if FILMENGINE_JXL
#include <thread>

#include "jxl/decode.h"
#include "jxl/thread_parallel_runner.h"
#endif

namespace film {

bool jxl_decode(const uint8_t *p, size_t n, std::vector<uint16_t> &out, int &width, int &height, int &channels,
                std::string &error)
{
#if FILMENGINE_JXL
  JxlDecoder *dec = JxlDecoderCreate(nullptr);
  void *runner = JxlThreadParallelRunnerCreate(nullptr, std::max(1u, std::min(8u, std::thread::hardware_concurrency())));
  struct Free
  {
    JxlDecoder *d;
    void *r;
    ~Free() { if(r) JxlThreadParallelRunnerDestroy(r); if(d) JxlDecoderDestroy(d); }
  } free_them{dec, runner};
  if(!dec)
  {
    error = "JPEG XL: no decoder";
    return false;
  }
  if(runner) JxlDecoderSetParallelRunner(dec, JxlThreadParallelRunner, runner);
  if(JxlDecoderSubscribeEvents(dec, JXL_DEC_BASIC_INFO | JXL_DEC_FULL_IMAGE) != JXL_DEC_SUCCESS ||
     JxlDecoderSetInput(dec, p, n) != JXL_DEC_SUCCESS)
  {
    error = "JPEG XL: decoder setup failed";
    return false;
  }
  JxlDecoderCloseInput(dec);
  JxlPixelFormat format{3, JXL_TYPE_UINT16, JXL_NATIVE_ENDIAN, 0};
  for(;;)
  {
    const JxlDecoderStatus status = JxlDecoderProcessInput(dec);
    if(status == JXL_DEC_BASIC_INFO)
    {
      JxlBasicInfo info;
      if(JxlDecoderGetBasicInfo(dec, &info) != JXL_DEC_SUCCESS)
      {
        error = "JPEG XL: no image header";
        return false;
      }
      width = static_cast<int>(info.xsize);
      height = static_cast<int>(info.ysize);
      channels = info.num_color_channels == 1 ? 1 : 3;
      format.num_channels = static_cast<uint32_t>(channels);
    }
    else if(status == JXL_DEC_NEED_IMAGE_OUT_BUFFER)
    {
      size_t bytes = 0;
      if(JxlDecoderImageOutBufferSize(dec, &format, &bytes) != JXL_DEC_SUCCESS)
      {
        error = "JPEG XL: no output size";
        return false;
      }
      out.resize(bytes / sizeof(uint16_t));
      if(JxlDecoderSetImageOutBuffer(dec, &format, out.data(), bytes) != JXL_DEC_SUCCESS)
      {
        error = "JPEG XL: output buffer refused";
        return false;
      }
    }
    else if(status == JXL_DEC_FULL_IMAGE || status == JXL_DEC_SUCCESS)
      return width > 0 && height > 0 && !out.empty();
    else
    {
      error = status == JXL_DEC_NEED_MORE_INPUT ? "JPEG XL: data cut short" : "JPEG XL: corrupt data";
      return false;
    }
  }
#else
  (void)p; (void)n; (void)out; (void)width; (void)height; (void)channels;
  error = "JPEG XL support not built";
  return false;
#endif
}

} // namespace film
