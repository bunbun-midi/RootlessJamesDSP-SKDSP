# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")

# Android 15+: support devices with 16 KB memory pages (independent of NDK version)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Declares and names the project.
project(jamesdsp LANGUAGES C)
project(jamesdsp-wrapper LANGUAGES CXX)

# Bungee time-stretch/pitch-shift library (git submodule, pinned to v2.4.30; MPL-2.0).
# Used by "Pitch Shifter HQ" and the speed shifter in libjamesdsp-wrapper.
set(BUNGEE_DIR ${CMAKE_CURRENT_LIST_DIR}/bungee)
if(NOT EXISTS ${BUNGEE_DIR}/src/Stretcher.cpp OR NOT EXISTS ${BUNGEE_DIR}/submodules/eigen/Eigen/Core)
    message(FATAL_ERROR "Bungee sources missing. Run: git submodule update --init --recursive")
endif()

add_library(pffft STATIC
        ${BUNGEE_DIR}/submodules/pffft/pffft.c
        ${BUNGEE_DIR}/submodules/pffft/fftpack.c)
target_include_directories(pffft PUBLIC ${BUNGEE_DIR}/submodules/pffft)
target_compile_options(pffft PRIVATE -ffast-math -fno-finite-math-only)

file(GLOB BUNGEE_SOURCE_FILES CONFIGURE_DEPENDS ${BUNGEE_DIR}/src/*.cpp)
add_library(bungee_static STATIC ${BUNGEE_SOURCE_FILES})
target_compile_features(bungee_static PUBLIC cxx_std_20)
target_include_directories(bungee_static
        PUBLIC ${BUNGEE_DIR}
        PRIVATE ${BUNGEE_DIR}/submodules ${BUNGEE_DIR}/submodules/eigen)
target_compile_definitions(bungee_static PRIVATE
        BUNGEE_SELF_TEST=0
        eigen_assert=BUNGEE_ASSERT1
        EIGEN_DONT_PARALLELIZE=1)
set_source_files_properties(${BUNGEE_DIR}/src/version.cpp PROPERTIES COMPILE_DEFINITIONS BUNGEE_VERSION=\"2.4.30\")
# IMPORTANT: Bungee uses NaN as a marker value (std::isnan). The global -Ofast implies
# -ffinite-math-only, which optimizes those checks away and makes Bungee loop forever.
target_compile_options(bungee_static PRIVATE -fwrapv -fno-finite-math-only)
target_link_libraries(bungee_static PRIVATE pffft log)
# Both static libraries end up inside the shared libjamesdsp-wrapper.so
set_target_properties(pffft bungee_static PROPERTIES POSITION_INDEPENDENT_CODE ON)

include(libcrashlytics-connector/CMakeLists.txt)
include(libjdspimptoolbox/CMakeLists.txt)
include(libjamesdsp-wrapper/CMakeLists.txt)

file(GLOB_RECURSE LIBJAMESDSP_SOURCE_FILES CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.c
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.h)

add_library( # Sets the name of the library.
        jamesdsp

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        ${LIBJAMESDSP_SOURCE_FILES}
        EELStdOutExtension.c
        EELStdOutExtension.h)

target_compile_options(jamesdsp PRIVATE -O2)
target_compile_options(jamesdsp PRIVATE -std=gnu11)
target_include_directories(jamesdsp PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/)

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

target_link_libraries( # Specifies the target library.
        jamesdsp

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib})
