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

# Sherpa is an optional, source-only VITS runtime. Normal builds remain the
# existing Kokoro/eSpeak build until a pinned checkout is supplied explicitly.
option(CITA_ENABLE_SHERPA_VITS "Build the source-pinned Sherpa VITS JNI bridge" OFF)
set(SHERPA_ONNX_SOURCE_DIR "" CACHE PATH "Pinned Sherpa-ONNX source directory")
set(CITA_ONNXRUNTIME_LIB_ROOT "" CACHE PATH "ONNX Runtime AAR jni root, containing one directory per Android ABI")
if(CITA_ENABLE_SHERPA_VITS)
    if(NOT IS_DIRECTORY "${SHERPA_ONNX_SOURCE_DIR}")
        message(FATAL_ERROR "CITA_ENABLE_SHERPA_VITS requires SHERPA_ONNX_SOURCE_DIR")
    endif()
    if(CITA_ONNXRUNTIME_LIB_ROOT)
        set(_cita_ort_lib_dir "${CITA_ONNXRUNTIME_LIB_ROOT}/${CMAKE_ANDROID_ARCH_ABI}")
        if(NOT IS_DIRECTORY "${_cita_ort_lib_dir}")
            message(FATAL_ERROR "ONNX Runtime ABI directory is missing: ${_cita_ort_lib_dir}")
        endif()
        set(ENV{SHERPA_ONNXRUNTIME_LIB_DIR} "${_cita_ort_lib_dir}")
    endif()
    # Build only the Sherpa VITS model/session implementation below.  Sherpa's
    # aggregate TTS target also links Piper phonemization, which is not the
    # frontend for this character VITS model.
    set(SHERPA_ONNX_ENABLE_TTS OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_BINARY OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_PYTHON OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_C_API ON CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_PORTAUDIO OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_WEBSOCKET OFF CACHE BOOL "" FORCE)
    add_subdirectory("${SHERPA_ONNX_SOURCE_DIR}" "sherpa-onnx" EXCLUDE_FROM_ALL)

    add_library(cita_sherpa_vits_model STATIC
        "${SHERPA_ONNX_SOURCE_DIR}/sherpa-onnx/csrc/offline-tts-vits-model-config.cc"
        "${SHERPA_ONNX_SOURCE_DIR}/sherpa-onnx/csrc/offline-tts-vits-model.cc"
    )
    target_include_directories(cita_sherpa_vits_model PRIVATE "${SHERPA_ONNX_SOURCE_DIR}")
    target_link_libraries(cita_sherpa_vits_model PRIVATE sherpa-onnx-core)

    add_library(cita_sherpa_vits SHARED native_sherpa_vits.cpp)
    target_compile_features(cita_sherpa_vits PRIVATE cxx_std_17)
    target_include_directories(cita_sherpa_vits PRIVATE
        "${SHERPA_ONNX_SOURCE_DIR}"
        "$ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR}"
    )
    target_link_libraries(cita_sherpa_vits PRIVATE cita_sherpa_vits_model)
endif()
