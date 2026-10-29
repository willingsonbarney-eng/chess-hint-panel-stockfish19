/*
 * Stockfish <-> Java bridge.
 *
 * Stockfish normally talks UCI on stdin/stdout of its own process.  On Android that
 * would mean executing a binary, which newer Android versions restrict.  Instead the
 * whole engine is compiled into a shared library (libstockfish.so), loaded with
 * System.loadLibrary() - the officially supported way - and the UCI loop is fed through
 * two custom stream buffers:
 *
 *     Java  --nativeWrite(cmd)-->  InQueue  --> std::cin  --> UCIEngine::loop
 *     UCIEngine::loop --> std::cout --> OutQueue --> Java.onEngineLine(line)
 *
 * The engine runs on its own thread inside the app process; nothing is ever executed
 * from disk and nothing leaves the phone.
 *
 * Life cycle (all of it exercised by tools/tests/EngineTest.java):
 *     nativeInit()      start the engine thread (no-op while it is already running)
 *     nativeWrite(cmd)  one UCI command line
 *     nativeShutdown()  "quit" + join the engine thread; nativeInit() can start it again
 *
 * Build flavours:
 *     default          Stockfish 19 (C++17, UCIEngine API, NNUE embedded in the library)
 *     -DSF_LEGACY_11   Stockfish 11 API - used ONLY by the desktop tests of this bridge
 */
#include <jni.h>
#include <pthread.h>

#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#ifdef SF_LEGACY_11
  #include "bitboard.h"
  #include "endgame.h"
  #include "position.h"
  #include "search.h"
  #include "thread.h"
  #include "tt.h"
  #include "uci.h"
  #include "syzygy/tbprobe.h"
  namespace PSQT { void init(); }
#else
  #include "attacks.h"
  #include "misc.h"
  #include "position.h"
  #include "tune.h"
  #include "uci.h"
#endif

static JavaVM*   g_vm = nullptr;
static jobject   g_obj = nullptr;
static jmethodID g_midLine = nullptr;

static std::mutex              g_lifeMutex;      // guards the thread life cycle below
static std::thread             g_thread;
static std::condition_variable g_lifeCv;
static bool                    g_running = false;   // engine thread inside engineMain
static pthread_key_t           g_detachKey;
static pthread_once_t          g_keyOnce = PTHREAD_ONCE_INIT;

// ----------------------------------------------------------------- input buffer

class InQueue : public std::streambuf {
public:
    void push(const std::string& s) {
        std::lock_guard<std::mutex> lk(m);
        for (char c : s) buf.push_back(c);
        if (buf.empty() || buf.back() != '\n') buf.push_back('\n');
        cv.notify_all();
    }
    /** forget everything that is still queued and accept input again */
    void reset() {
        std::lock_guard<std::mutex> lk(m);
        buf.clear();
        closed_ = false;
        setg(nullptr, nullptr, nullptr);
    }
    /** unblock a reader that is waiting for input (engine thread leaves its loop on EOF) */
    void close() {
        std::lock_guard<std::mutex> lk(m);
        closed_ = true;
        cv.notify_all();
    }
protected:
    int_type underflow() override {
        std::unique_lock<std::mutex> lk(m);
        while (buf.empty() && !closed_) cv.wait(lk);
        if (buf.empty()) return traits_type::eof();
        ch = buf.front();
        buf.pop_front();
        setg(&ch, &ch, &ch + 1);
        return traits_type::to_int_type(ch);
    }
private:
    std::mutex m;
    std::condition_variable cv;
    std::deque<char> buf;
    char ch = 0;
    bool closed_ = false;
};

// ----------------------------------------------------------------- output buffer

static void javaLine(const std::string& line);

class OutQueue : public std::streambuf {
protected:
    int_type overflow(int_type c) override {
        if (traits_type::eq_int_type(c, traits_type::eof())) return traits_type::not_eof(c);
        char ch = traits_type::to_char_type(c);
        std::lock_guard<std::mutex> lk(m);
        if (ch == '\n') { std::string l; l.swap(line); javaLine(l); }
        else if (ch != '\r') line.push_back(ch);
        return c;
    }
    int sync() override {
        std::lock_guard<std::mutex> lk(m);
        if (!line.empty()) { std::string l; l.swap(line); javaLine(l); }
        return 0;
    }
private:
    std::mutex m;
    std::string line;
};

static InQueue  g_in;
static OutQueue g_out;

// A thread that attached itself to the VM must detach before it exits, otherwise ART aborts the
// whole app ("Native thread exiting without having called DetachCurrentThread").  The engine has
// several threads (UCI loop + search workers) that print, so every one of them detaches through a
// pthread key destructor.
static void detachAtExit(void*) {
    if (g_vm) g_vm->DetachCurrentThread();
}
static void makeKey() { pthread_key_create(&g_detachKey, detachAtExit); }

static JNIEnv* envForThisThread() {
    static thread_local JNIEnv* env = nullptr;
    if (env) return env;
    if (!g_vm) return nullptr;
    pthread_once(&g_keyOnce, makeKey);
#ifdef __ANDROID__
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) { env = nullptr; return nullptr; }
#else
    if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) { env = nullptr; return nullptr; }
#endif
    pthread_setspecific(g_detachKey, reinterpret_cast<void*>(1));    // arms the destructor
    return env;
}

static void javaLine(const std::string& line) {
    jobject obj = g_obj;
    jmethodID mid = g_midLine;
    if (!g_vm || !obj || !mid) return;
    JNIEnv* env = envForThisThread();
    if (!env) return;
    jstring s = env->NewStringUTF(line.c_str());
    if (!s) { if (env->ExceptionCheck()) env->ExceptionClear(); return; }
    env->CallVoidMethod(obj, mid, s);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(s);
}

// ----------------------------------------------------------------- engine thread

static void engineMain() {
    std::cin.rdbuf(&g_in);
    std::cout.rdbuf(&g_out);
    std::cerr.rdbuf(&g_out);

#ifdef SF_LEGACY_11
    UCI::init(Options);
    PSQT::init();
    Bitboards::init();
    Position::init();
    Bitbases::init();
    Endgames::init();
    Threads.set(Options["Threads"]);
    Search::clear();
    UCI::loop(1, nullptr);               // returns after "quit" (or end of input)
    Threads.set(0);
#else
    static bool tablesReady = false;     // process wide, idempotent
    if (!tablesReady) {
        Stockfish::Attacks::init();
        Stockfish::Position::init();
        tablesReady = true;
    }
    {
        static char arg0[] = "stockfish";
        static char* argv[] = {arg0, nullptr};
        Stockfish::CommandLine cli(1, argv);
        // one engine object per run: destroying it joins every search thread, so a restart
        // starts from a completely clean state
        auto uci = std::make_unique<Stockfish::UCIEngine>(std::move(cli));
        Stockfish::Tune::init(uci->engine_options());
        uci->loop();                     // returns after "quit" (or end of input)
    }
#endif
    std::cout.flush();
    {
        std::lock_guard<std::mutex> lk(g_lifeMutex);
        g_running = false;
        g_lifeCv.notify_all();
    }
    // pthread key destructor detaches this thread from the VM when it exits
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_chesshint_panel_UciEngine_nativeInit(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lk(g_lifeMutex);
    if (g_obj) {
        env->DeleteGlobalRef(g_obj);
        g_obj = nullptr;
    }
    g_obj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_midLine = env->GetMethodID(cls, "onEngineLine", "(Ljava/lang/String;)V");
    if (g_running) return;                       // already running: only the callback target changed
    if (g_thread.joinable()) g_thread.join();    // a previous run that already finished
    g_in.reset();
    g_running = true;
    g_thread = std::thread(engineMain);
}

JNIEXPORT void JNICALL
Java_com_chesshint_panel_UciEngine_nativeWrite(JNIEnv* env, jobject thiz, jstring cmd) {
    if (!cmd) return;
    const char* c = env->GetStringUTFChars(cmd, nullptr);
    if (c) {
        g_in.push(c);
        env->ReleaseStringUTFChars(cmd, c);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_chesshint_panel_UciEngine_nativeIsRunning(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lk(g_lifeMutex);
    return g_running ? JNI_TRUE : JNI_FALSE;
}

/** "quit", then wait (at most timeoutMs) for the engine thread to finish; true = it is gone */
JNIEXPORT jboolean JNICALL
Java_com_chesshint_panel_UciEngine_nativeShutdown(JNIEnv* env, jobject thiz, jint timeoutMs) {
    {
        std::unique_lock<std::mutex> lk(g_lifeMutex);
        if (!g_running) {
            if (g_thread.joinable()) g_thread.join();
            return JNI_TRUE;
        }
    }
    g_in.push("quit");
    std::unique_lock<std::mutex> lk(g_lifeMutex);
    bool done = g_lifeCv.wait_for(lk, std::chrono::milliseconds(timeoutMs > 0 ? timeoutMs : 3000),
                                  [] { return !g_running; });
    if (!done) {
        g_in.close();                           // last resort: end of input also ends the loop
        done = g_lifeCv.wait_for(lk, std::chrono::milliseconds(1500), [] { return !g_running; });
    }
    if (done && g_thread.joinable()) g_thread.join();
    return done ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
