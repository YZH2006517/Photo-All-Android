# Film engine: vkdt filmsim compute shaders + a standalone Vulkan host.
#   Android: added from the app's CMakeLists (NDK provides glslc and Vulkan headers).
#   Desktop: cmake -S engine -B build -DFILMENGINE_NDK=F:/Android/Sdk/ndk/<version>
cmake_minimum_required(VERSION 3.22)
project(filmengine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID)
  set(FILMENGINE_NDK "${ANDROID_NDK}")
else()
  set(FILMENGINE_NDK "" CACHE PATH "Android NDK, used for glslc and the Vulkan headers")
endif()
file(GLOB _glslc_dirs "${FILMENGINE_NDK}/shader-tools/*")
find_program(GLSLC NAMES glslc HINTS ${_glslc_dirs} REQUIRED)

# ---- shaders -----------------------------------------------------------------
set(VKDT_SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/vkdt")
set(OWN_SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/own")
file(GLOB_RECURSE VKDT_SHADER_DEPS CONFIGURE_DEPENDS "${VKDT_SHADERS}/*.glsl" "${VKDT_SHADERS}/*.h")
set(SPV_DIR "${CMAKE_CURRENT_BINARY_DIR}/spv")
set(SPV_FILES "")
foreach(kernel setup dirlut fast expose develop halin halout scatter negprint)
  list(APPEND KERNEL_SOURCES "${VKDT_SHADERS}/filmsim/${kernel}.comp")
endforeach()
foreach(kernel blur downsample decode encode raw fusion chroma flashmix flashfrac flashwb diffpick diffuse finish)
  list(APPEND KERNEL_SOURCES "${OWN_SHADERS}/${kernel}.comp")
endforeach()
# raw denoise, adapted from vkdt (see shaders/denoise/README.md)
set(DENOISE_SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/denoise")
foreach(kernel dn_half dn_downcov dn_down dn_assemble dn_doub)
  list(APPEND KERNEL_SOURCES "${DENOISE_SHADERS}/${kernel}.comp")
endforeach()
file(GLOB DENOISE_SHADER_DEPS CONFIGURE_DEPENDS "${DENOISE_SHADERS}/*.glsl")
list(APPEND VKDT_SHADER_DEPS ${DENOISE_SHADER_DEPS})
foreach(source IN LISTS KERNEL_SOURCES)
  get_filename_component(kernel "${source}" NAME_WE)
  get_filename_component(source_dir "${source}" DIRECTORY)
  set(spv "${SPV_DIR}/${kernel}.spv")
  add_custom_command(
    OUTPUT "${spv}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${SPV_DIR}"
    COMMAND "${GLSLC}" --target-env=vulkan1.1 -O -I "${VKDT_SHADERS}" -I "${source_dir}" "${source}" -o "${spv}"
    DEPENDS "${source}" ${VKDT_SHADER_DEPS}
    COMMENT "glslc ${kernel}.comp"
    VERBATIM)
  list(APPEND SPV_FILES "${spv}")
  # the same kernel unoptimised ("<kernel>_safe"): for a GPU driver whose compiler crashes on
  # the optimised SPIR-V (a Huawei Kirin driver on setup).
  set(safe "${SPV_DIR}/${kernel}_safe.spv")
  add_custom_command(
    OUTPUT "${safe}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${SPV_DIR}"
    COMMAND "${GLSLC}" --target-env=vulkan1.1 -O0 -I "${VKDT_SHADERS}" -I "${source_dir}" "${source}" -o "${safe}"
    DEPENDS "${source}" ${VKDT_SHADER_DEPS}
    COMMENT "glslc ${kernel}.comp (unoptimised)"
    VERBATIM)
  list(APPEND SPV_FILES "${safe}")
endforeach()
set(SHADER_CPP "${CMAKE_CURRENT_BINARY_DIR}/film_shaders.cpp")
add_custom_command(
  OUTPUT "${SHADER_CPP}"
  COMMAND "${CMAKE_COMMAND}" "-DOUTPUT=${SHADER_CPP}" "-DSPV_FILES=${SPV_FILES}"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
  DEPENDS ${SPV_FILES} "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
  COMMENT "embedding SPIR-V"
  VERBATIM)

# ---- LibRaw: camera RAW files that are not DNG (CDDL 1.0, see libraw/README.md) -----
file(GLOB_RECURSE LIBRAW_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/libraw/src/*.cpp")
# the *_ph.cpp placeholders are not part of LibRaw's own build (its Makefile leaves them out)
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")
add_library(libraw_engine STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/libraw")
target_compile_definitions(libraw_engine PUBLIC LIBRAW_NODLL PRIVATE LIBRAW_NOTHREADS)
if(MSVC)
  target_compile_definitions(libraw_engine PRIVATE _CRT_SECURE_NO_WARNINGS)
  target_compile_options(libraw_engine PRIVATE /w /O2 /utf-8)
else()
  # its code is optimised in every build, and only what the engine calls is linked
  target_compile_options(libraw_engine PRIVATE -w -O2 -ffunction-sections -fdata-sections)
endif()

# ---- libjxl: JPEG XL pieces of DNG 1.7 files (BSD-3-Clause; Highway Apache-2.0/BSD-3,
# Brotli MIT, skcms BSD-3), decoder only, from third_party/libjxl when it is there ----------
set(LIBJXL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libjxl" CACHE PATH "libjxl checkout (decoder only)")
set(FILMENGINE_JXL OFF)
if(EXISTS "${LIBJXL_DIR}/CMakeLists.txt")
  set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
  set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
  foreach(off JPEGXL_ENABLE_TOOLS JPEGXL_ENABLE_JPEGLI JPEGXL_ENABLE_JPEGLI_LIBJPEG JPEGXL_ENABLE_DOXYGEN
      JPEGXL_ENABLE_MANPAGES JPEGXL_ENABLE_BENCHMARK JPEGXL_ENABLE_EXAMPLES JPEGXL_ENABLE_JNI JPEGXL_ENABLE_SJPEG
      JPEGXL_ENABLE_OPENEXR JPEGXL_ENABLE_TRANSCODE_JPEG JPEGXL_ENABLE_BOXES JPEGXL_ENABLE_FUZZERS JPEGXL_ENABLE_DEVTOOLS
      JPEGXL_ENABLE_VIEWERS JPEGXL_ENABLE_PLUGINS JPEGXL_ENABLE_TCMALLOC JPEGXL_ENABLE_SKCMS JPEGXL_ENABLE_AVX512
      JPEGXL_ENABLE_AVX512_SPR JPEGXL_ENABLE_AVX512_ZEN4 JPEGXL_BUNDLE_LIBPNG JPEGXL_ENABLE_WASM_THREADS
      JPEGXL_ENABLE_JPEGLI_TESTS JPEGXL_INSTALL_JPEGLI_LIBJPEG)
    set(${off} OFF CACHE BOOL "" FORCE)
  endforeach()
  set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE) # colour transforms the decoder needs
  # one SIMD target (the baseline: NEON on arm64), not one copy per CPU feature level
  add_compile_definitions(HWY_COMPILE_ONLY_STATIC=1)
  add_subdirectory("${LIBJXL_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/libjxl" EXCLUDE_FROM_ALL)
  if(NOT MSVC)
    # optimised in every build (a debug build's -O0 makes a 12 MP decode crawl), and small
    foreach(t jxl_dec-obj jxl_threads hwy brotlicommon brotlidec jxl_cms skcms)
      if(TARGET ${t})
        target_compile_options(${t} PRIVATE -O2 -ffunction-sections -fdata-sections)
      endif()
    endforeach()
  endif()
  set(FILMENGINE_JXL ON)
endif()

# ---- library -----------------------------------------------------------------
add_library(filmengine_core STATIC
  src/burst.cpp
  src/camera_raw.cpp
  src/jxl_tile.cpp
  src/dng.cpp
  src/film_engine.cpp
  src/film_params.cpp
  src/vk_api.cpp
  "${SHADER_CPP}")
target_include_directories(filmengine_core PUBLIC src PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_definitions(filmengine_core PUBLIC VK_NO_PROTOTYPES)
target_link_libraries(filmengine_core PRIVATE libraw_engine)
if(FILMENGINE_JXL)
  target_link_libraries(filmengine_core PRIVATE jxl_dec jxl_threads)
  target_compile_definitions(filmengine_core PRIVATE FILMENGINE_JXL=1)
endif()

if(ANDROID)
  target_link_libraries(filmengine_core PUBLIC dl)
else()
  # Only the Vulkan headers are taken from the NDK sysroot; its libc headers must not leak in.
  set(_sysroot_include "")
  file(GLOB _sysroots "${FILMENGINE_NDK}/toolchains/llvm/prebuilt/*/sysroot/usr/include")
  list(GET _sysroots 0 _sysroot_include)
  set(VK_INCLUDE "${CMAKE_CURRENT_BINARY_DIR}/vulkan-headers")
  file(COPY "${_sysroot_include}/vulkan" "${_sysroot_include}/vk_video" DESTINATION "${VK_INCLUDE}")
  target_include_directories(filmengine_core PUBLIC "${VK_INCLUDE}")
  if(MSVC)
    # C4305: wb.h tables are double literals in float arrays
    target_compile_options(filmengine_core PRIVATE /W3 /utf-8 /wd4305)
  else()
    target_link_libraries(filmengine_core PUBLIC ${CMAKE_DL_LIBS})
  endif()
  add_executable(filmsim_cli desktop/filmsim_cli.cpp)
  target_link_libraries(filmsim_cli PRIVATE filmengine_core)
  add_executable(bracket_test desktop/bracket_test.cpp)
  target_link_libraries(bracket_test PRIVATE filmengine_core)
endif()
