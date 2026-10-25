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

# Encoders for the recorder: MP3 (LAME, LGPL) and Ogg Vorbis (libogg + libvorbis, BSD)
#   app/src/main/cpp/lame    LAME 3.100 source (lame-3.100.tar.gz from SourceForge, extracted & renamed)
#   app/src/main/cpp/ogg     libogg 1.3.6 (https://github.com/xiph/ogg), only the files needed to build
#   app/src/main/cpp/vorbis  libvorbis 1.3.7 (https://github.com/xiph/vorbis), only the encoder's files
set(LAME_DIR ${CMAKE_CURRENT_LIST_DIR}/lame)
set(OGG_DIR ${CMAKE_CURRENT_LIST_DIR}/ogg)
set(VORBIS_DIR ${CMAKE_CURRENT_LIST_DIR}/vorbis)
if(NOT EXISTS ${LAME_DIR}/libmp3lame/lame.c)
    message(FATAL_ERROR "LAME sources missing in ${LAME_DIR}: download lame-3.100.tar.gz from https://sourceforge.net/projects/lame/files/lame/3.100/, extract it and rename the folder to 'lame'")
endif()
if(NOT EXISTS ${OGG_DIR}/src/framing.c OR NOT EXISTS ${VORBIS_DIR}/lib/vorbisenc.c)
    message(FATAL_ERROR "libogg/libvorbis sources missing in ${OGG_DIR} / ${VORBIS_DIR} (libogg 1.3.6 and libvorbis 1.3.7 from github.com/xiph)")
endif()

# Codec libraries are built without -ffast-math (inherited from -Ofast): they rely on exact float semantics
set(CODEC_COMPILE_OPTIONS -fno-fast-math -UDEBUG -w)

file(GLOB LAME_SOURCE_FILES ${LAME_DIR}/libmp3lame/*.c)
add_library(mp3lame STATIC ${LAME_SOURCE_FILES})
target_include_directories(mp3lame
        PUBLIC ${LAME_DIR}/include
        PRIVATE ${LAME_DIR}/libmp3lame ${CMAKE_CURRENT_LIST_DIR}/codec-config/lame)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3lame PRIVATE ${CODEC_COMPILE_OPTIONS})

# libogg normally generates config_types.h with its configure step
set(OGG_GENERATED_INCLUDE ${CMAKE_CURRENT_BINARY_DIR}/ogg-generated)
file(WRITE ${OGG_GENERATED_INCLUDE}/ogg/config_types.h "#ifndef __CONFIG_TYPES_H__
#define __CONFIG_TYPES_H__
#include <stdint.h>
typedef int16_t ogg_int16_t;
typedef uint16_t ogg_uint16_t;
typedef int32_t ogg_int32_t;
typedef uint32_t ogg_uint32_t;
typedef int64_t ogg_int64_t;
typedef uint64_t ogg_uint64_t;
#endif
")
add_library(ogg STATIC ${OGG_DIR}/src/bitwise.c ${OGG_DIR}/src/framing.c)
target_include_directories(ogg PUBLIC ${OGG_DIR}/include ${OGG_GENERATED_INCLUDE})
target_compile_options(ogg PRIVATE ${CODEC_COMPILE_OPTIONS})

set(VORBIS_SOURCE_FILES mdct.c smallft.c block.c envelope.c window.c lsp.c lpc.c analysis.c synthesis.c
        psy.c info.c floor1.c floor0.c res0.c mapping0.c registry.c codebook.c sharedbook.c lookup.c
        bitrate.c vorbisenc.c)
list(TRANSFORM VORBIS_SOURCE_FILES PREPEND ${VORBIS_DIR}/lib/)
add_library(vorbis STATIC ${VORBIS_SOURCE_FILES})
target_include_directories(vorbis PUBLIC ${VORBIS_DIR}/include PRIVATE ${VORBIS_DIR}/lib)
target_compile_options(vorbis PRIVATE ${CODEC_COMPILE_OPTIONS})
target_link_libraries(vorbis PUBLIC ogg)

set_target_properties(mp3lame ogg vorbis PROPERTIES POSITION_INDEPENDENT_CODE ON)

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
