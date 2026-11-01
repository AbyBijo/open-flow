#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_abybijo_openflow_whisper_WhisperEngine_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;
    whisper_context_params params = whisper_context_default_params();
    // Android has no GPU backend in this build: force CPU-only, do not
    // rely on a desktop GPU default falling back silently.
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
    params.language = "auto";
    params.translate = false;
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_context = true;
    // Short utterances: one segment avoids over-segmentation. Long ones
    // keep normal segmentation.
    params.single_segment = (size < 16000 * 10);
    params.suppress_blank = true;
    params.suppress_non_speech_tokens = true;
    params.no_speech_thold = 0.6f;
    // Scale the audio context to the utterance length instead of paying
    // the full 30 s encoder cost for every short utterance.
    {
        int seconds = static_cast<int>(size / 16000) + 1;
        int ctx = (seconds * 1500) / 30 + 128;
        if (ctx < 256) ctx = 256;
        if (ctx > 1500) ctx = 1500;
        params.audio_ctx = ctx;
    }
    // Proven Android thread count: min(4, cores). More threads contend
    // with the audio/UI threads and throttle on a phone.
    {
        unsigned cores = std::thread::hardware_concurrency();
        if (cores == 0) cores = 4;
        if (cores > 4) cores = 4;
        params.n_threads = static_cast<int>(cores);
    }

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
