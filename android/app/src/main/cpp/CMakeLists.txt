cmake_minimum_required(VERSION 3.22)
project(filmapp CXX)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../../engine" "${CMAKE_CURRENT_BINARY_DIR}/engine")

add_library(filmjni SHARED film_jni.cpp)
target_link_libraries(filmjni PRIVATE filmengine_core android jnigraphics log)
