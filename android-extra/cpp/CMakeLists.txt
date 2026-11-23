cmake_minimum_required(VERSION 3.10)
project(clipenhance)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp -O3")
# OpenMP must be linked statically: Android has no libomp.so for apps to load
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -fopenmp -static-openmp -Wl,--gc-sections")

# the prebuilt ncnn (Android + Vulkan) is unpacked here by the build workflow
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn CACHE PATH "ncnn package")
find_package(ncnn REQUIRED)

add_library(clipenhance SHARED enhance.cpp)
target_link_libraries(clipenhance ncnn log)
