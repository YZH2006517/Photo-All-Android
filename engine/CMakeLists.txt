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
foreach(kernel blur downsample decode encode raw fusion chroma flashmix flashfrac flashwb)
  list(APPEND KERNEL_SOURCES "${OWN_SHADERS}/${kernel}.comp")
endforeach()
# HDR+ burst align and merge, ported from hdr-plus-swift (GPL-3.0; see shaders/hdrplus/README.md)
foreach(kernel hp_quad hp_pyramid hp_align hp_warp hp_merge hp_bayer)
  list(APPEND KERNEL_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/shaders/hdrplus/${kernel}.comp")
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
endforeach()
set(SHADER_CPP "${CMAKE_CURRENT_BINARY_DIR}/film_shaders.cpp")
add_custom_command(
  OUTPUT "${SHADER_CPP}"
  COMMAND "${CMAKE_COMMAND}" "-DOUTPUT=${SHADER_CPP}" "-DSPV_FILES=${SPV_FILES}"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
  DEPENDS ${SPV_FILES} "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
  COMMENT "embedding SPIR-V"
  VERBATIM)

# ---- library -----------------------------------------------------------------
add_library(filmengine_core STATIC
  src/burst.cpp
  src/dng.cpp
  src/film_engine.cpp
  src/film_params.cpp
  src/vk_api.cpp
  "${SHADER_CPP}")
target_include_directories(filmengine_core PUBLIC src PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_definitions(filmengine_core PUBLIC VK_NO_PROTOTYPES)

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
