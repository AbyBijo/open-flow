#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_abybijo_openflow_whisper_WhisperEngine_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;
    whisper_context_params params = whisper_context_default_params();
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
    params.single_segment = false;

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
