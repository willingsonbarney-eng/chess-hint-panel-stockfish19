/*
 * Stockfish 19 <-> Java UCI bridge.
 *
 * The upstream Stockfish engine stays inside libstockfish.so. Its UCI loop runs on a
 * dedicated thread with private stdin/stdout stream buffers, so Android does not execute
 * a loose native binary from app storage. All analysis is local and offline.
 *
 * This file intentionally uses the Stockfish 19 UCIEngine API (sf_19), not the obsolete
 * pre-SF12 global UCI::loop/Options API.
 */
#include <jni.h>

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include "attacks.h"
#include "misc.h"
#include "position.h"
#include "tune.h"
#include "uci.h"

namespace {
JavaVM* g_vm = nullptr;
jobject g_obj = nullptr;  // global ref; replaced only when no old engine thread is running
jmethodID g_midLine = nullptr;
std::atomic<bool> g_started{false};
std::mutex g_java_ref_mutex;

class InQueue final : public std::streambuf {
public:
    void push(const std::string& text) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (char c : text) buffer_.push_back(c);
        if (text.empty() || text.back() != '\n') buffer_.push_back('\n');
        ready_.notify_all();
    }

protected:
    int_type underflow() override {
        std::unique_lock<std::mutex> lock(mutex_);
        ready_.wait(lock, [this] { return !buffer_.empty(); });
        current_ = buffer_.front();
        buffer_.pop_front();
        setg(&current_, &current_, &current_ + 1);
        return traits_type::to_int_type(current_);
    }

private:
    std::mutex mutex_;
    std::condition_variable ready_;
    std::deque<char> buffer_;
    char current_ = 0;
};

class OutQueue final : public std::streambuf {
protected:
    int_type overflow(int_type c) override {
        if (traits_type::eq_int_type(c, traits_type::eof()))
            return traits_type::not_eof(c);
        const char ch = traits_type::to_char_type(c);
        if (ch == '\n') {
            emit(line_);
            line_.clear();
        } else if (ch != '\r') {
            line_.push_back(ch);
        }
        return c;
    }

    int sync() override {
        if (!line_.empty()) {
            emit(line_);
            line_.clear();
        }
        return 0;
    }

private:
    static void emit(const std::string& line);
    std::string line_;
};

InQueue g_in;
OutQueue g_out;

void OutQueue::emit(const std::string& line) {
    if (!g_vm || line.empty()) return;
    JNIEnv* env = nullptr;
    bool attachedHere = false;
#ifdef __ANDROID__
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attachedHere = true;
    }
#else
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
        attachedHere = true;
    }
#endif
    {
        std::lock_guard<std::mutex> lock(g_java_ref_mutex);
        if (g_obj && g_midLine) {
            jstring value = env->NewStringUTF(line.c_str());
            if (value) {
                env->CallVoidMethod(g_obj, g_midLine, value);
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(value);
            }
        }
    }
    if (attachedHere) g_vm->DetachCurrentThread();
}

void engineMain() {
    // The stockfish UCI loop expects synchronous stdin/stdout. These are redirected only
    // on the engine thread; callbacks are line-oriented and non-networked.
    std::cin.clear();
    std::cin.rdbuf(&g_in);
    std::cout.rdbuf(&g_out);
    std::cerr.rdbuf(&g_out);

    try {
        Stockfish::Attacks::init();
        Stockfish::Position::init();
        char argv0[] = "stockfish";
        char* argv[] = {argv0};
        Stockfish::CommandLine cli(1, argv);
        Stockfish::UCIEngine uci(std::move(cli));
        Stockfish::Tune::init(uci.engine_options());
        uci.loop();
        std::cout.flush();
    } catch (...) {
        // Stockfish is normally built with exceptions disabled. Keep the JNI thread's
        // lifecycle flag correct if a nonstandard local build enables exceptions.
        std::cerr << "info string native engine loop terminated unexpectedly\n";
    }
    g_started.store(false, std::memory_order_release);
}
}  // namespace

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_com_chesshint_panel_UciEngine_nativeInit(JNIEnv* env, jobject thiz) {
    {
        std::lock_guard<std::mutex> lock(g_java_ref_mutex);
        if (g_obj) env->DeleteGlobalRef(g_obj);
        g_obj = env->NewGlobalRef(thiz);
        jclass cls = env->GetObjectClass(thiz);
        g_midLine = env->GetMethodID(cls, "onEngineLine", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    bool expected = false;
    if (!g_started.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) return;
    try {
        std::thread(engineMain).detach();
    } catch (...) {
        g_started.store(false, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL Java_com_chesshint_panel_UciEngine_nativeWrite(JNIEnv* env, jobject, jstring cmd) {
    if (!cmd) return;
    const char* chars = env->GetStringUTFChars(cmd, nullptr);
    if (!chars) return;
    g_in.push(chars);
    env->ReleaseStringUTFChars(cmd, chars);
}

JNIEXPORT jboolean JNICALL Java_com_chesshint_panel_UciEngine_nativeIsRunning(JNIEnv*, jobject) {
    return g_started.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
}  // extern "C"
