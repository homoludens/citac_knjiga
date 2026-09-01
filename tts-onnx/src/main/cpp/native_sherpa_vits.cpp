#include <jni.h>

#include <array>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <vector>

#include "onnxruntime_cxx_api.h"
#include "sherpa-onnx/csrc/offline-tts-model-config.h"
#include "sherpa-onnx/csrc/offline-tts-vits-model.h"

namespace {
struct VitsHandle {
    std::unique_ptr<sherpa_onnx::OfflineTtsVitsModel> model;
};

void throwJava(JNIEnv *env, const char *message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_homoludens_citacknjiga_tts_onnx_SherpaVitsJni_open(
    JNIEnv *env, jobject, jstring model_path, jstring tokens_path, jstring lexicon_path) {
    (void)tokens_path;
    (void)lexicon_path;
    if (model_path == nullptr) {
        throwJava(env, "Sherpa VITS model path must not be null");
        return 0;
    }
    const char *model = env->GetStringUTFChars(model_path, nullptr);
    if (model == nullptr) {
        throwJava(env, "Unable to read Sherpa VITS model path");
        return 0;
    }
    try {
        sherpa_onnx::OfflineTtsModelConfig config;
        config.provider = "cpu";
        config.num_threads = 1;
        config.vits.model = model;
        auto handle = std::make_unique<VitsHandle>();
        handle->model = std::make_unique<sherpa_onnx::OfflineTtsVitsModel>(config);
        env->ReleaseStringUTFChars(model_path, model);
        return reinterpret_cast<jlong>(handle.release());
    } catch (const std::exception &failure) {
        env->ReleaseStringUTFChars(model_path, model);
        throwJava(env, failure.what());
    } catch (...) {
        env->ReleaseStringUTFChars(model_path, model);
        throwJava(env, "Unknown Sherpa VITS model error");
    }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_homoludens_citacknjiga_tts_onnx_SherpaVitsJni_generate(
    JNIEnv *env, jobject, jlong raw_handle, jintArray token_ids, jint speaker_id, jfloat speed) {
    auto *handle = reinterpret_cast<VitsHandle *>(raw_handle);
    if (handle == nullptr || handle->model == nullptr || token_ids == nullptr || speed <= 0) {
        throwJava(env, "Invalid Sherpa VITS generation arguments");
        return nullptr;
    }
    try {
        const jsize size = env->GetArrayLength(token_ids);
        if (size == 0) throw std::invalid_argument("Sherpa VITS token sequence is empty");
        std::vector<jint> ids(static_cast<size_t>(size));
        env->GetIntArrayRegion(token_ids, 0, size, ids.data());
        std::vector<int64_t> input(ids.begin(), ids.end());
        const std::array<int64_t, 2> shape{1, static_cast<int64_t>(input.size())};
        auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        auto tensor = Ort::Value::CreateTensor<int64_t>(memory, input.data(), input.size(), shape.data(), shape.size());
        auto output = handle->model->Run(std::move(tensor), speaker_id, speed);
        int64_t sample_count = 1;
        for (const auto dimension : output.GetTensorTypeAndShapeInfo().GetShape()) sample_count *= dimension;
        const float *samples = output.GetTensorData<float>();
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(sample_count));
        if (result == nullptr) return nullptr;
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(sample_count), samples);
        return result;
    } catch (const std::exception &failure) {
        throwJava(env, failure.what());
    } catch (...) {
        throwJava(env, "Unknown Sherpa VITS generation error");
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_homoludens_citacknjiga_tts_onnx_SherpaVitsJni_sampleRate(
    JNIEnv *env, jobject, jlong raw_handle) {
    auto *handle = reinterpret_cast<VitsHandle *>(raw_handle);
    if (handle == nullptr || handle->model == nullptr) {
        throwJava(env, "Sherpa VITS session is invalid");
        return 0;
    }
    return handle->model->GetMetaData().sample_rate;
}

extern "C" JNIEXPORT void JNICALL
Java_com_homoludens_citacknjiga_tts_onnx_SherpaVitsJni_close(
    JNIEnv *, jobject, jlong raw_handle) {
    delete reinterpret_cast<VitsHandle *>(raw_handle);
}
