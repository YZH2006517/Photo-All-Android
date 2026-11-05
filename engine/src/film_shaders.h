// SPIR-V for every kernel, embedded at build time (see cmake/embed_spirv.cmake).
#pragma once
#include <cstddef>

namespace film {

struct ShaderBlob
{
  const char *name;
  const unsigned char *code; // 4-byte aligned
  size_t size;
};

const ShaderBlob *find_shader(const char *name);

} // namespace film
