// Thin JNI bridge over whisper.cpp: load a model file, transcribe
// 16 kHz mono float samples, free the context. Nothing else lives here.
//
// Two lessons from v1 are baked in and must not be "improved" away:
//  - Pin the language to "en" and keep whisper's defaults. Letting the
//    decoder auto-detect the language on short utterances made
//    on-device dictation return empty text.
//  - CPU only. Android has no GPU backend in this build.
//
// v2.4.2 corrects an older rule that used to live here ("never shrink
// audio_ctx"): openWispr measured that sizing audio_ctx to the actual
// clip is ~2x faster on the encoder for typical 3-8s dictations, with
// negligible accuracy cost — the 768 floor keeps the decoder from
// looping on very short clips. Stock Whisper zero-pads every clip to
// 30s and runs the full encoder over the padding, so the old rule was
// paying for 30s of encoder work on a 3s utterance.
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
    // Size the encoder context to the actual clip (openWispr's
    // measured formula): stock Whisper zero-pads the mel to 30s
    // (audio_ctx=1500) and runs the full encoder over it, so a short
    // dictation wastes ~7x encoder compute. needed is the clip length
    // in encoder tokens (~50/sec) plus a small margin, rounded up to
    // a multiple of 64 for hardware tiling; the 768 floor keeps the
    // decoder from looping on very short clips, and 1500 is Whisper's
    // 30s maximum. Typical 3-8s clips land at the 768 floor, about
    // twice as fast on the encoder, negligible accuracy cost.
    {
        int needed = (size + 319) / 320 + 16; // ceil(size/16000*50) + 16
        int ctx = ((needed + 63) / 64) * 64;
        if (ctx < 768) ctx = 768;
        if (ctx > 1500) ctx = 1500;
        params.audio_ctx = ctx;
    }
    // Greedy at temperature 0: the fewest wasted decode steps. The
    // temperature fallback (temperature_inc, default 0.2) is deliberately
    // left on — it is what catches a repetition loop and re-runs the
    // clip hotter, so a rare bad decode costs latency instead of
    // looping forever. Non-speech tokens are suppressed for the same
    // reason: fewer wasted steps.
    params.temperature = 0.0f;
    params.suppress_nst = true;
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
