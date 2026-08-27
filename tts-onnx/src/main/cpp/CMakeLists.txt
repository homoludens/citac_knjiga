cmake_minimum_required(VERSION 3.22)

project(cita-espeak LANGUAGES C CXX VERSION 1.52.0)

include(FetchContent)

set(ESPEAK_NG_SOURCE_DIR "" CACHE PATH "Pinned eSpeak-NG source directory")
if(NOT ESPEAK_NG_SOURCE_DIR)
    FetchContent_Declare(
        espeak_ng
        GIT_REPOSITORY https://github.com/espeak-ng/espeak-ng.git
        GIT_TAG 4870adfa25b1a32b4361592f1be8a40337c58d6c
    )
    FetchContent_Populate(espeak_ng)
    set(ESPEAK_NG_SOURCE_DIR "${espeak_ng_SOURCE_DIR}")
endif()

if(NOT IS_DIRECTORY "${ESPEAK_NG_SOURCE_DIR}/src/libespeak-ng")
    message(FATAL_ERROR "Pinned eSpeak-NG source is missing: ${ESPEAK_NG_SOURCE_DIR}")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(HAVE_MKSTEMP 1)
set(USE_ASYNC OFF)
set(USE_KLATT OFF)
set(USE_LIBPCAUDIO OFF)
set(USE_LIBSONIC OFF)
set(USE_MBROLA OFF)
set(USE_SPEECHPLAYER OFF)

add_library(espeak-include INTERFACE)
target_include_directories(espeak-include INTERFACE
    "${ESPEAK_NG_SOURCE_DIR}/src/include"
    "${ESPEAK_NG_SOURCE_DIR}/src/include/compat"
)

add_subdirectory("${ESPEAK_NG_SOURCE_DIR}/src/ucd-tools" "espeak-ucd")
add_subdirectory("${ESPEAK_NG_SOURCE_DIR}/src/libespeak-ng" "espeak-lib")

add_library(cita_espeak SHARED native_espeak.cpp)
target_compile_features(cita_espeak PRIVATE cxx_std_17)
target_include_directories(cita_espeak PRIVATE
    "${ESPEAK_NG_SOURCE_DIR}/src/include"
    "${ESPEAK_NG_SOURCE_DIR}/src/ucd-tools/src/include"
)
target_link_libraries(cita_espeak PRIVATE espeak-ng)
