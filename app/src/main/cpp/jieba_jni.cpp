#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cppjieba/DictTrie.hpp"
#include "cppjieba/HMMModel.hpp"
#include "cppjieba/MixSegment.hpp"
#include "cppjieba/PosTagger.hpp"
#include "cppjieba/QuerySegment.hpp"

#define LOG_TAG "JiebaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct JiebaState {
    std::unique_ptr<cppjieba::DictTrie> dict;
    std::unique_ptr<cppjieba::HMMModel> hmm;
    std::unique_ptr<cppjieba::MixSegment> mixSeg;
    std::unique_ptr<cppjieba::QuerySegment> querySeg;
};

std::mutex g_mutex;
std::unique_ptr<JiebaState> g_state;

std::string joinPath(const std::string& dir, const std::string& file) {
    if (dir.empty()) return file;
    if (dir.back() == '/') return dir + file;
    return dir + "/" + file;
}

std::string toStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jobjectArray toStringArray(JNIEnv* env, const std::vector<std::string>& words) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(words.size()), stringClass, nullptr);
    if (arr == nullptr) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(words.size()); ++i) {
        jstring w = env->NewStringUTF(words[i].c_str());
        env->SetObjectArrayElement(arr, i, w);
        env->DeleteLocalRef(w);
    }
    return arr;
}

jobjectArray emptyStringArray(JNIEnv* env) {
    jclass stringClass = env->FindClass("java/lang/String");
    return env->NewObjectArray(0, stringClass, nullptr);
}

}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeInit(
    JNIEnv* env, jobject, jstring dictDir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state) return JNI_TRUE;

    const std::string dir = toStdString(env, dictDir);
    if (dir.empty()) {
        LOGE("nativeInit: empty dict dir");
        return JNI_FALSE;
    }

    try {
        auto state = std::make_unique<JiebaState>();
        const std::string dictPath = joinPath(dir, "jieba.dict.utf8");
        const std::string hmmPath = joinPath(dir, "hmm_model.utf8");

        state->dict = std::make_unique<cppjieba::DictTrie>(dictPath);
        state->hmm = std::make_unique<cppjieba::HMMModel>(hmmPath);

        state->mixSeg = std::make_unique<cppjieba::MixSegment>(state->dict.get(), state->hmm.get());
        state->querySeg = std::make_unique<cppjieba::QuerySegment>(state->dict.get(), state->hmm.get());

        g_state = std::move(state);
        LOGI("nativeInit: loaded dictionaries from %s", dir.c_str());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("nativeInit failed: %s", e.what());
        g_state.reset();
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeInit failed: unknown error");
        g_state.reset();
        return JNI_FALSE;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeSegment(
    JNIEnv* env, jobject, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state) {
        LOGE("nativeSegment before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::string> words;

        g_state->mixSeg->Cut(sentence, words,  true);
        return toStringArray(env, words);
    } catch (const std::exception& e) {
        LOGE("nativeSegment failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativeSegment failed: unknown error");
        return emptyStringArray(env);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeSegmentForSearch(
    JNIEnv* env, jobject, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state) {
        LOGE("nativeSegmentForSearch before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::string> words;
        g_state->querySeg->Cut(sentence, words,  true);
        return toStringArray(env, words);
    } catch (const std::exception& e) {
        LOGE("nativeSegmentForSearch failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativeSegmentForSearch failed: unknown error");
        return emptyStringArray(env);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativePosTag(
    JNIEnv* env, jobject, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state) {
        LOGE("nativePosTag before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::pair<std::string, std::string> > tagged;
        cppjieba::PosTagger tagger;

        tagger.Tag(sentence, tagged, *g_state->mixSeg);
        std::vector<std::string> flat;
        flat.reserve(tagged.size() * 2);
        for (size_t i = 0; i < tagged.size(); ++i) {
            flat.push_back(tagged[i].first);
            flat.push_back(tagged[i].second);
        }
        return toStringArray(env, flat);
    } catch (const std::exception& e) {
        LOGE("nativePosTag failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativePosTag failed: unknown error");
        return emptyStringArray(env);
    }
}

}
