cmake_minimum_required(VERSION 3.22.1)
project(voztranslate_m2m100 LANGUAGES C CXX)

include(FetchContent)
if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
endif()

# SentencePiece source and license are pinned to the upstream v0.2.0 commit.
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_ABSL_PROVIDER "internal" CACHE STRING "" FORCE)
FetchContent_Declare(
    sentencepiece
    GIT_REPOSITORY https://github.com/google/sentencepiece.git
    GIT_TAG 17d7580d6407802f85855d2cc9190634e2c95624
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(sentencepiece)
if(NOT sentencepiece_POPULATED)
    FetchContent_Populate(sentencepiece)
endif()

# SentencePiece v0.2.0 injects an unquoted -fmacro-prefix-map containing the absolute
# source path. It breaks Android Clang when this checkout's Windows path contains spaces.
# Drop only that optional diagnostics/path-shortening flag; model/library sources stay pinned.
set(_spm_prefix_map_block [=[if((CMAKE_CXX_COMPILER_ID STREQUAL "Clang" AND
     CMAKE_CXX_COMPILER_VERSION VERSION_GREATER 10.0) OR
   (CMAKE_CXX_COMPILER_ID STREQUAL "GNU" AND
     CMAKE_CXX_COMPILER_VERSION VERSION_GREATER 8.0))
  string(APPEND CMAKE_CXX_FLAGS " -fmacro-prefix-map=${CMAKE_SOURCE_DIR}/=''")
endif()]=])
file(READ "${sentencepiece_SOURCE_DIR}/CMakeLists.txt" _spm_cmake)
string(FIND "${_spm_cmake}" "${_spm_prefix_map_block}" _spm_prefix_map_index)
if(NOT _spm_prefix_map_index LESS 0)
    string(REPLACE "${_spm_prefix_map_block}" "" _spm_cmake "${_spm_cmake}")
else()
    # Reconfiguration can reuse the source tree already patched by the previous CMake run.
    string(FIND "${_spm_cmake}" "-fmacro-prefix-map" _spm_remaining_prefix_map)
    if(NOT _spm_remaining_prefix_map LESS 0)
        message(FATAL_ERROR "SentencePiece v0.2.0 CMake changed; inspect its path-map flag before building.")
    endif()
endif()
file(WRITE "${sentencepiece_SOURCE_DIR}/CMakeLists.txt" "${_spm_cmake}")
unset(_spm_prefix_map_block)
unset(_spm_cmake)
add_subdirectory("${sentencepiece_SOURCE_DIR}" "${sentencepiece_BINARY_DIR}" EXCLUDE_FROM_ALL)

add_library(voztranslate_m2m100 SHARED m2m100_jni.cpp)
target_compile_features(voztranslate_m2m100 PRIVATE cxx_std_17)
target_compile_options(voztranslate_m2m100 PRIVATE -fexceptions -frtti)
target_include_directories(voztranslate_m2m100 PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/onnxruntime-1.23.2/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/nlohmann-json-3.11.3
    ${sentencepiece_SOURCE_DIR}/src
    ${sentencepiece_BINARY_DIR}/src
)
target_link_libraries(voztranslate_m2m100 PRIVATE sentencepiece-static log dl)
