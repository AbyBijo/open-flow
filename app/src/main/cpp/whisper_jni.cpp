// Thin JNI bridge over whisper.cpp: load a model file, transcribe
// 16 kHz mono float samples, free the context. Nothing else lives here.
//
// Two lessons from v1 are baked in and must not be "improved" away:
//  - Pin the language to "en" and keep whisper's defaults. Letting the
//    decoder auto-detect the language on short utterances, or shrinking
//    the encoder context, made on-device dictation return empty text.
//  - CPU only. Android has no GPU backend in this build.
#include <jni.h>
#include <cstdlib>
#include <fstream>
#include <string>
#include <thread>
#include <vector>
#include "whisper.h"

namespace {

// ARM efficiency ("little") cores, by part number: Cortex-A53,
// A55, A510, A520. Everything else counts as a big core.
bool isLittleCore(int part) {
    return part == 0xd03 || part == 0xd05 || part == 0xd46 || part == 0xd80;
}

// Thread count from the phone's own CPU layout, read from
// /proc/cpuinfo ("CPU part" lines, hex). Same rule as openWispr's
// WhisperCpuConfig, re-implemented here: on a big.LITTLE part the
// little cores absorb the OS work, so all big cores are used; on an
// all-big part two cores are handed back to the system. At most 6
// threads, never below the old cap of 4, and 4 whenever cpuinfo
// cannot be read.
int pickThreadCount() {
    int totalCores = static_cast<int>(std::thread::hardware_concurrency());
    if (totalCores <= 0) totalCores = 4;
    int bigCores = 0;
    int partLines = 0;
    std::ifstream cpuinfo("/proc/cpuinfo");
    if (cpuinfo) {
        std::string line;
        while (std::getline(cpuinfo, line)) {
            if (line.rfind("CPU part", 0) != 0) continue;
            const std::size_t hexPos = line.find("0x");
            if (hexPos == std::string::npos) continue;
            const long part = std::strtol(line.c_str() + hexPos, nullptr, 16);
            if (part <= 0) continue;
            ++partLines;
            if (!isLittleCore(static_cast<int>(part))) ++bigCores;
        }
    }
    if (partLines == 0 || bigCores <= 0) return 4;
    int threads = (bigCores < totalCores) ? bigCores : bigCores - 2;
    if (threads < 4) threads = 4;
    if (threads > 6) threads = 6;
    return threads;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_abybijo_openflow_whisper_WhisperEngine_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(cpath, params);
    env->ReleaseStringUTFChars(path, cpath);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_abybijo_openflow_whisper_WhisperEngine_nativeTranscribe(
    JNIEnv* env, jobject /* thiz */, jlong handle, jfloatArray pcm) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx == nullptr || pcm == nullptr) return env->NewStringUTF("");
    jsize size = env->GetArrayLength(pcm);
    if (size <= 0) return env->NewStringUTF("");
    std::vector<float> samples(static_cast<size_t>(size));
    env->GetFloatArrayRegion(pcm, 0, size, samples.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.detect_language = false;
    params.translate = false;
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.suppress_blank = true;
    params.no_speech_thold = 0.6f;
    // Decode the whole clip as one segment (openWispr does the same
    // for English): splitting it only costs decode time.
    params.single_segment = true;
    // audio_ctx is deliberately left at its default (0 = the model's
    // own 1500). Scaling it down for short utterances breaks the
    // encoder context the model was trained with.
    params.n_threads = pickThreadCount();

    if (whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size())) != 0) {
        return env->NewStringUTF("");
    }
    std::string result;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            if (!result.empty()) result += " ";
            result += text;
        }
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_abybijo_openflow_whisper_WhisperEngine_nativeFree(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

} // extern "C"
