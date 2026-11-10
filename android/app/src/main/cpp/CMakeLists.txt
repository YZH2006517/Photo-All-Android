cmake_minimum_required(VERSION 3.22)
project(filmapp CXX)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../../engine" "${CMAKE_CURRENT_BINARY_DIR}/engine")
# The engine's CPU steps (DNG decode, burst alignment, staging) run per shot on megapixel
# frames: an -O0 debug build makes them 10-20x slower, so they are optimised in every build.
target_compile_options(filmengine_core PRIVATE -O3)

add_library(filmjni SHARED film_jni.cpp digicam.cpp)
# per-pixel work (digicam, bitmap copies): optimised in debug builds too
target_compile_options(filmjni PRIVATE -O3)
target_link_libraries(filmjni PRIVATE filmengine_core android jnigraphics log)
