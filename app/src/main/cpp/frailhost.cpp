#include <jni.h>
#include <string>
#include <vector>
#include <set>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <time.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <pthread.h>
#include <elf.h>

static JavaVM* gVm = nullptr;

static const char* kMarkers[] = {
    "frida", "gum-js", "gmain", "linjector", "libinjector", "substrate",
    "xposed", "riru", "zygisk", "magisk", "kernelsu", "apatch", "lspatch",
    "pine", "sandhook", "whale", "bhook", "adbi", "ddi", "libhook",
    "cycript", "libcycript", "gadget", "libalex",
};

static const char* kFridaSockets[] = {
    "/dev/frida-server", "/dev/frida-agent", "/data/local/tmp/frida-server",
    "/data/local/tmp/frida", "/data/local/tmp/re.frida.server",
};

static const char* kHookImplicationTracers[] = {
    "ptrace-traceme-refused", "timing-skew", "rwx-anon-mapping",
    "got-entry-outside", "inline-prologue-changed",
};

static bool containsMarker(const std::string& hay) {
    if (hay.empty()) return false;
    std::string low = hay;
    std::transform(low.begin(), low.end(), low.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    for (const char* m : kMarkers) {
        if (low.find(m) != std::string::npos) return true;
    }
    return false;
}

static std::string currentThreadName() {
    char buf[32] = {0};
    pthread_getname_np(pthread_self(), buf, sizeof(buf));
    return std::string(buf);
}

static bool ptraceBlocked() {
    // PTRACE_TRACEME  succeeds  once  per  process:  a  second  caller  (or  an
    // already-attached debugger) makes it fail, which is the signal we want.
    long r = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
    if (r == 0) {
        ptrace(PTRACE_DETACH, 0, nullptr, nullptr);
        return false;
    }
    return true;
}

static int readTracerPid() {
    std::ifstream st("/proc/self/status");
    std::string line;
    while (std::getline(st, line)) {
        if (line.rfind("TracerPid:", 0) == 0) {
            return std::atoi(line.c_str() + 10);
        }
    }
    return -1;
}

static bool readStatusBlob(std::string& out) {
    std::ifstream st("/proc/self/status");
    if (!st) return false;
    std::ostringstream oss;
    oss << st.rdbuf();
    out = oss.str();
    return true;
}

static std::vector<std::string> scanMaps() {
    std::vector<std::string> hits;
    std::ifstream maps("/proc/self/maps");
    std::set<std::string> seen;
    std::string line;
    while (std::getline(maps, line)) {
        std::string::size_type p = line.find('/');
        if (p == std::string::npos) continue;
        std::string path = line.substr(p);
        if (seen.count(path)) continue;
        seen.insert(path);
        if (containsMarker(path)) hits.push_back(path);
    }
    return hits;
}

static bool hasRwxAnon() {
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        //  "XXXX-yyyy rwxp ..."  -  writable+executable anonymous  memory  is  a
        //  strong  JIT /  injector  indicator  for  a  process  that  never  asks
        //  for it.
        if (line.find(" rwxp ") != std::string::npos) {
            if (line.find('/') == std::string::npos) return true;
        }
    }
    return false;
}

static std::vector<std::string> scanThreads() {
    std::vector<std::string> hits;
    DIR* d = opendir("/proc/self/task");
    if (!d) return hits;
    while (struct dirent* e = readdir(d)) {
        if (e->d_name[0] == '.') continue;
        std::ostringstream oss;
        oss << "/proc/self/task/" << e->d_name << "/comm";
        std::ifstream f(oss.str());
        if (!f) continue;
        std::string name;
        std::getline(f, name);
        if (!name.empty() && containsMarker(name)) hits.push_back(name);
    }
    closedir(d);
    return hits;
}

static std::vector<std::string> scanFridaSockets() {
    std::vector<std::string> hits;
    for (const char* p : kFridaSockets) {
        struct stat st;
        if (::stat(p, &st) == 0) hits.push_back(p);
    }
    //  Named  pipes  frida-server  creates  under /data/local/tmp  are  visible
    //  through  the  filesystem  even  when  the  process  itself  is  renamed.
    DIR* d = opendir("/data/local/tmp");
    if (d) {
        while (struct dirent* e = readdir(d)) {
            std::string n = e->d_name;
            if (n.find("frida") != std::string::npos || n.find("gum") != std::string::npos) {
                hits.push_back("/data/local/tmp/" + n);
            }
        }
        closedir(d);
    }
    return hits;
}

static uint64_t timingSkewNs() {
    //  A  hooked  clock  or  a  ptrace-stopped  process  shows  up  as  a  wildly
    //  inflated  or  suspiciously  round  delay  around  a  fixed  amount  of
    //  work.  We  deliberately  do  not  compare  against  a  threshold  here:
    //  the  Kotlin  layer  samples  this  repeatedly  and  looks  at  variance.
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    volatile uint64_t acc = 0;
    for (int i = 0; i < 4096; ++i) acc += static_cast<uint64_t>(i) * 31u;
    clock_gettime(CLOCK_MONOTONIC, &b);
    uint64_t ns = static_cast<uint64_t>(b.tv_sec - a.tv_sec) * 1000000000ull +
                  static_cast<uint64_t>(b.tv_nsec - a.tv_nsec);
    return ns + (acc == 0 ? 1u : 0u);
}

static bool inlinePrologueChanged(const void* fn) {
    //  `stp  x29, x30, [sp, #-16]!`  is  the  universal  AArch64  function
    //  prologue.  Inline  hooks  overwrite  exactly  these  bytes  with  a  jump,
    //  so  the  first  instruction  no  longer  decodes  as  a  frame  push.
    const uint32_t* words = static_cast<const uint32_t*>(fn);
    uint32_t w = words[0];
    //  stp  x29, x30, [sp, #-16]!   ->  0xA9BF7BFD  (little-endian encoding)
    return (w & 0xFFC07FFFu) != 0xA9807BFDu;
}

static bool gotEntryOutside(void* sym) {
    //  A  resolved  GOT  entry  must  point  into  an  executable  mapping.  An
    //  attacker  repointing  it  at  a  tampered  copy  lands  in  an  anonymous
    //  or  non-executable  region,  which  is  what  we  test  for  here.
    std::ifstream maps("/proc/self/maps");
    std::string line;
    uintptr_t addr = reinterpret_cast<uintptr_t>(sym);
    while (std::getline(maps, line)) {
        uintptr_t lo = 0, hi = 0;
        if (std::sscanf(line.c_str(), "%lx-%lx", &lo, &hi) != 2) continue;
        if (addr >= lo && addr < hi) {
            return line.find(" x") == std::string::npos;
        }
    }
    return true;
}

static jstring newString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

static jobjectArray stringArray(JNIEnv* env, const std::vector<std::string>& v) {
    jclass stringCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(v.size()), stringCls, nullptr);
    for (size_t i = 0; i < v.size(); ++i) {
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), newString(env, v[i]));
    }
    return arr;
}

static void pushImplication(std::vector<std::string>& out, const char* tag) {
    out.push_back(tag);
}

static void runProbe(bool& debugger, int& tracer,
                     std::vector<std::string>& bridges,
                     std::vector<std::string>& implications) {
    bridges.clear();
    implications.clear();

    std::vector<std::string> maps = scanMaps();
    std::vector<std::string> threads = scanThreads();
    std::vector<std::string> sockets = scanFridaSockets();
    bridges.insert(bridges.end(), maps.begin(), maps.end());
    bridges.insert(bridges.end(), threads.begin(), threads.end());
    bridges.insert(bridges.end(), sockets.begin(), sockets.end());

    std::string self = currentThreadName();
    if (containsMarker(self)) bridges.push_back("self:" + self);

    int tr = readTracerPid();
    tracer = tr;
    debugger = (tr > 0);

    if (ptraceBlocked()) pushImplication(implications, kHookImplicationTracers[0]);
    if (hasRwxAnon()) pushImplication(implications, kHookImplicationTracers[2]);
    if (timingSkewNs() == 0) pushImplication(implications, kHookImplicationTracers[1]);

    std::string status;
    if (readStatusBlob(status)) {
        //  A  non-zero  TracerPid  that  vanished  between  the  Java  read  and
        //  this  read  is  the  classic  signature  of  a  tracer  that  hooks
        //  open()  on  /proc  to  hide  itself.
        if (status.find("TracerPid:\t0") == std::string::npos && tr <= 0) {
            pushImplication(implications, "status-anomaly");
        }
    }

    void* selfSym = reinterpret_cast<void*>(&runProbe);
    if (inlinePrologueChanged(selfSym)) {
        pushImplication(implications, kHookImplicationTracers[4]);
    }
    if (gotEntryOutside(selfSym)) {
        pushImplication(implications, kHookImplicationTracers[3]);
    }
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openminis_app_hostfrail_NativeProbe_nativeProbe(JNIEnv* env, jobject /*thiz*/) {
    bool debugger = false;
    int tracer = -1;
    std::vector<std::string> bridges;
    std::vector<std::string> implications;
    runProbe(debugger, tracer, bridges, implications);

    jclass owner = env->FindClass("com/openminis/app/hostfrail/NativeProbe");
    if (owner == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    jmethodID cb = env->GetStaticMethodID(owner, "onNativeObservation",
        "(ZILjava/lang/String;[Ljava/lang/String;I[Ljava/lang/String;)V");
    if (cb == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }

    //  descriptor: (ZILjava/lang/String;[Ljava/lang/String;I[Ljava/lang/String;)V
    //  =  debugger, tracer, selfThreadName, bridges[], implicationCount, implications[]
    jstring selfStr = newString(env, currentThreadName());
    jobjectArray bArr = stringArray(env, bridges);
    jobjectArray iArr = stringArray(env, implications);
    env->CallStaticVoidMethod(owner, cb, static_cast<jboolean>(debugger),
                              static_cast<jint>(tracer), selfStr, bArr,
                              static_cast<jint>(implications.size()), iArr);
    env->DeleteLocalRef(selfStr);
    env->DeleteLocalRef(bArr);
    env->DeleteLocalRef(iArr);
    env->DeleteLocalRef(owner);

    return static_cast<jboolean>(!bridges.empty() || debugger);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openminis_app_hostfrail_NativeProbe_nativeThreadName(JNIEnv* env, jobject /*thiz*/) {
    return newString(env, currentThreadName());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openminis_app_hostfrail_NativeProbe_nativeMapsDigest(JNIEnv* env, jobject /*thiz*/) {
    //  FNV-1a  over  the  set  of  mapped  paths,  sorted,  so  the  digest  is
    //  stable  across  ASLR  and  only  changes  when  the  mapping  set  does.
    std::ifstream maps("/proc/self/maps");
    std::set<std::string> paths;
    std::string line;
    while (std::getline(maps, line)) {
        std::string::size_type p = line.find('/');
        if (p == std::string::npos) continue;
        paths.insert(line.substr(p));
    }
    uint64_t h = 1469598103934665603ull;
    for (const std::string& s : paths) {
        for (char c : s) {
            h ^= static_cast<uint64_t>(static_cast<unsigned char>(c));
            h *= 1099511628211ull;
        }
        h ^= 0x9e3779b97f4a7c15ull;
    }
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%016llx/%zu",
                  static_cast<unsigned long long>(h), paths.size());
    return newString(env, buf);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_openminis_app_hostfrail_NativeProbe_nativeTimingSample(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(timingSkewNs());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openminis_app_hostfrail_NativeProbe_nativeStatusBlob(JNIEnv* env, jobject /*thiz*/) {
    std::string blob;
    if (!readStatusBlob(blob)) return newString(env, std::string());
    //  Only  the  fields  that  carry  debug /  trace  state;  the  rest  is
    //  noise  we  do  not  want  sitting  in  a  log  line.
    std::ostringstream oss;
    for (const char* key : {"TracerPid:", "State:", "Threads:", "SigBlk:", "CapBnd:"}) {
        std::string::size_type p = blob.find(key);
        if (p == std::string::npos) continue;
        std::string::size_type e = blob.find('\n', p);
        oss << blob.substr(p, e == std::string::npos ? std::string::npos : e - p) << '\n';
    }
    return newString(env, oss.str());
}
