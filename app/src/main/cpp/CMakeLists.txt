cmake_minimum_required(VERSION 3.22.1)
project(openflow_whisper)

include(FetchContent)

# whisper.cpp is fetched when the app builds, so the repo stays small.
# Only the library is built — no examples, tests or server.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.7.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(openflow_whisper SHARED whisper_jni.cpp)
target_link_libraries(openflow_whisper PRIVATE whisper)
