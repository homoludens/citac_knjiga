#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <mutex>
#include <string>

#include <espeak-ng/espeak_ng.h>
#include <espeak-ng/speak_lib.h>

namespace {

std::mutex g_mutex;
bool g_initialized = false;
std::string g_data_root;

bool append_utf8(std::string& output, std::uint32_t code_point) {
    if (code_point <= 0x7f) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0x10ffff) {
        output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        return false;
    }
    return true;
}

bool jstring_to_utf8(JNIEnv* env, jstring value, std::string& output) {
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return false;

    const jsize length = env->GetStringLength(value);
    output.clear();
    for (jsize index = 0; index < length; ++index) {
        std::uint32_t code_point = chars[index];
        if (code_point >= 0xd800 && code_point <= 0xdbff) {
            if (index + 1 >= length) {
                env->ReleaseStringChars(value, chars);
                return false;
            }
            const std::uint32_t low = chars[++index];
            if (low < 0xdc00 || low > 0xdfff) {
                env->ReleaseStringChars(value, chars);
                return false;
            }
            code_point = 0x10000 + ((code_point - 0xd800) << 10) + (low - 0xdc00);
        } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
            env->ReleaseStringChars(value, chars);
            return false;
        }
        if (!append_utf8(output, code_point)) {
            env->ReleaseStringChars(value, chars);
            return false;
        }
    }
    env->ReleaseStringChars(value, chars);
    return true;
}

bool initialize(const std::string& data_root) {
    if (g_initialized) return g_data_root == data_root;

    espeak_ng_InitializePath(data_root.c_str());
    espeak_ng_ERROR_CONTEXT context = nullptr;
    if (espeak_ng_Initialize(&context) != ENS_OK) {
        espeak_ng_ClearErrorContext(&context);
        return false;
    }
    if (espeak_ng_InitializeOutput(ENOUTPUT_MODE_SYNCHRONOUS, 0, nullptr) != ENS_OK ||
        espeak_ng_SetVoiceByName("sr") != ENS_OK) {
        espeak_ng_Terminate();
        return false;
    }

    g_initialized = true;
    g_data_root = data_root;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_homoludens_citacknjiga_tts_onnx_preprocessing_NativeEspeakBridge_phonemizeNative(
    JNIEnv* env,
    jclass,
    jstring text,
    jstring data_root) {
    if (text == nullptr || data_root == nullptr) return nullptr;

    std::string utf8_text;
    std::string utf8_data_root;
    if (!jstring_to_utf8(env, text, utf8_text) ||
        !jstring_to_utf8(env, data_root, utf8_data_root)) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!initialize(utf8_data_root)) return nullptr;

    // The pinned reference command's bulk --stdin path drops the final byte
    // before calling espeak_Synth, including when that byte is UTF-8 data.
    if (!utf8_text.empty()) utf8_text.pop_back();

    char* phonemes = nullptr;
    std::size_t phoneme_length = 0;
    FILE* trace = open_memstream(&phonemes, &phoneme_length);
    if (trace == nullptr) return nullptr;

    constexpr int phoneme_mode = espeakPHONEMES_IPA | espeakPHONEMES_TIE | (0x200d << 8);
    espeak_SetPhonemeTrace(phoneme_mode, trace);
    const int synth_flags = espeakCHARS_AUTO | espeakPHONEMES | espeakENDPAUSE;
    const espeak_ERROR synth_status = espeak_Synth(
        utf8_text.c_str(),
        utf8_text.size() + 1,
        0,
        POS_CHARACTER,
        0,
        synth_flags,
        nullptr,
        nullptr
    );
    const espeak_ng_STATUS sync_status = espeak_ng_Synchronize();
    fflush(trace);
    espeak_SetPhonemeTrace(0, nullptr);
    fclose(trace);

    if (synth_status != EE_OK || sync_status != ENS_OK || phonemes == nullptr) {
        std::free(phonemes);
        return nullptr;
    }
    jstring output = env->NewStringUTF(phonemes);
    std::free(phonemes);
    return output;
}
