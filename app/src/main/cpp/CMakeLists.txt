cmake_minimum_required(VERSION 3.22.1)
project(openflow_whisper)

include(FetchContent)

# whisper.cpp is fetched when the app builds, so the repo stays small.
# Only the library is built — no examples, tests or server.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# The native engine must never run unoptimised. CI builds a Debug APK,
# and Debug defaults to -O0 — which made on-device dictation 5-10x
# slower than the same model in an optimised build (openWispr forces
# -O3 + -march=armv8.2-a+fp16 + -flto). So full optimisation is forced
# here for EVERY build type, including Debug, on every target built
# from this file (whisper.cpp, ggml and our JNI bridge). The per-config
# flags are appended after the generic ones, so Debug's default -O0
# would win over -O3 above — they are overridden explicitly below.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(OPENFLOW_OPT_FLAGS "-O3 -march=armv8.2-a+fp16 -flto")
else()
    set(OPENFLOW_OPT_FLAGS "-O3 -flto")
endif()
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${OPENFLOW_OPT_FLAGS} -fvisibility=hidden -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OPENFLOW_OPT_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS_DEBUG "-O3 -g")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto -Wl,--gc-sections -Wl,--exclude-libs,ALL")

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.7.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(openflow_whisper SHARED whisper_jni.cpp)
target_link_libraries(openflow_whisper PRIVATE whisper)
