#include <chrono>
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
Java_com_abybijo_openflow_whisper_WhisperEngine_nativeTranscribeDiag(
    JNIEnv* env, jobject /* thiz */, jlong handle, jfloatArray pcm, jboolean lenient) {
    auto start = std::chrono::steady_clock::now();
    auto diag = [&](int segments, float noSpeech, const std::string& text) {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
        std::string out = std::to_string(segments) + "|" +
            std::to_string(noSpeech) + "|" + std::to_string(ms) + "|" + text;
        return env->NewStringUTF(out.c_str());
    };
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx == nullptr || pcm == nullptr) return diag(0, 1.0f, "");
    jsize size = env->GetArrayLength(pcm);
    if (size <= 0) return diag(0, 1.0f, "");
    std::vector<float> samples(static_cast<size_t>(size));
    env->GetFloatArrayRegion(pcm, 0, size, samples.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // Verified against whisper.cpp v1.7.5 whisper.h / whisper.cpp:
    // the DEFAULT language is "en" and audio_ctx defaults to 0 (use the
    // model's 1500). The previous code broke both of those defaults and
    // that is why dictation came back empty on-device:
    //  - language "auto" forces auto-detection on every short utterance;
    //    detection can fail or pick the wrong language and return "".
    //  - audio_ctx scaled down to 256 for short utterances rewrites the
    //    encoder context the model was trained with (1500) — experimental,
    //    not a proven pattern, and it changes KV-cache / positional
    //    handling for every short utterance.
    // So: keep whisper's defaults, only pin English and set threads.
    params.language = "en";
    params.detect_language = false;
    params.translate = false;
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.suppress_blank = true;
    // NOTE: whisper.cpp v1.7.5 has no suppress_non_speech_tokens member
    // in whisper_full_params (run #8 failed on exactly that line) — do
    // not re-add it. suppress_blank + no_speech_thold are the supported
    // anti-silence settings in this version.
    // Lenient retry (Retry button): accept much quieter / less certain
    // speech instead of discarding it as no-speech.
    params.no_speech_thold = lenient ? 0.2f : 0.6f;
    if (lenient) params.suppress_blank = false;
    // audio_ctx intentionally left at its default (0 = model default
    // 1500). Do NOT scale it down for short utterances.
    // single_segment intentionally left at its default (false).
    // Proven Android thread count: min(4, cores). More threads contend
    // with the audio/UI threads and throttle on a phone.
    {
        unsigned cores = std::thread::hardware_concurrency();
        if (cores == 0) cores = 4;
        if (cores > 4) cores = 4;
        params.n_threads = static_cast<int>(cores);
    }

    if (whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size())) != 0) {
        return diag(0, 1.0f, "");
    }
    std::string result;
    const int segments = whisper_full_n_segments(ctx);
    float noSpeech = 1.0f;
    for (int i = 0; i < segments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            if (!result.empty()) result += " ";
            result += text;
        }
        if (i == 0) noSpeech = whisper_full_get_segment_no_speech_prob(ctx, i);
    }
    return diag(segments, noSpeech, result);
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
