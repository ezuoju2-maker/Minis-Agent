#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cstdio>
#include <ctime>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/log.h>
#include <unwind.h>
#include <dlfcn.h>

#define LOG_TAG "MinisCrashHandler"

static char g_log_dir[512] = {0};

static volatile sig_atomic_t g_in_handler = 0;

static struct sigaction g_prev[NSIG];
static volatile sig_atomic_t g_prev_valid[NSIG];

struct BacktraceState {
    void** frames;
    int count;
    int capacity;
};

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context* ctx, void* arg) {
    BacktraceState* st = static_cast<BacktraceState*>(arg);
    const uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc != 0) {
        if (st->count >= st->capacity) return _URC_END_OF_STACK;
        st->frames[st->count++] = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

static const char* base_name(const char* path) {
    if (path == nullptr) return nullptr;
    const char* slash = strrchr(path, '/');
    return (slash != nullptr && slash[1] != '\0') ? slash + 1 : path;
}

static void write_backtrace(int fd) {
    void* frames[32];
    BacktraceState st = { frames, 0, 32 };
    _Unwind_Backtrace(unwind_cb, &st);
    if (st.count <= 0) return;

    const char* hdr = "\nBacktrace (lib+offset resolved via dladdr; feed the\n"
                      "offset straight to `llvm-addr2line -Cfe <lib>.so <offset>`,\n"
                      "or symbolize the whole file with `ndk-stack -sym <dir>`):\n";
    write(fd, hdr, strlen(hdr));

    for (int i = 0; i < st.count; i++) {

        char line[256];
        int n;

        Dl_info info;

        if (dladdr(frames[i], &info) != 0 && info.dli_fname != nullptr) {
            const char* lib = base_name(info.dli_fname);

            const uintptr_t pc = reinterpret_cast<uintptr_t>(frames[i]);
            const uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
            const uintptr_t off = (pc >= base) ? (pc - base) : 0;

            if (info.dli_sname != nullptr) {

                n = snprintf(line, sizeof(line), "  #%02d %p  %s+0x%lx (%s)\n",
                             i, frames[i], lib, static_cast<unsigned long>(off),
                             info.dli_sname);
            } else {
                n = snprintf(line, sizeof(line), "  #%02d %p  %s+0x%lx\n",
                             i, frames[i], lib, static_cast<unsigned long>(off));
            }
        } else {

            n = snprintf(line, sizeof(line), "  #%02d %p  <unknown>\n",
                         i, frames[i]);
        }

        if (n > 0) write(fd, line, static_cast<size_t>(n));
    }
}

static char g_abort_msg[512] = {0};
static volatile sig_atomic_t g_abort_msg_set = 0;

static void minis_aborter(const char* msg) {
    if (msg != nullptr && g_abort_msg_set == 0) {
        size_t i = 0;
        for (; i < sizeof(g_abort_msg) - 1 && msg[i] != '\0'; i++) {
            g_abort_msg[i] = msg[i];
        }
        g_abort_msg[i] = '\0';
        g_abort_msg_set = 1;
    }
}

struct minis_abort_msg_t {
    size_t size;
    char msg[0];
};

static const char* read_bionic_abort_message() {

    void* sym = dlsym(RTLD_DEFAULT, "__abort_message");
    if (sym == nullptr) return nullptr;
    minis_abort_msg_t* const* slot =
        reinterpret_cast<minis_abort_msg_t* const*>(sym);
    if (slot == nullptr) return nullptr;
    const minis_abort_msg_t* block = *slot;
    if (block == nullptr) return nullptr;

    if (block->size <= sizeof(size_t) || block->size > 64 * 1024) return nullptr;
    if (block->msg[0] == '\0') return nullptr;
    return block->msg;
}

static const char* abort_message_or_null() {
    if (g_abort_msg_set != 0 && g_abort_msg[0] != '\0') return g_abort_msg;
    return read_bionic_abort_message();
}

static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSYS:  return "SIGSYS";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

static void chain_to_previous(int sig, siginfo_t* info, void* ctx) {
    if (sig > 0 && sig < NSIG && g_prev_valid[sig]) {
        const struct sigaction& prev = g_prev[sig];
        if ((prev.sa_flags & SA_SIGINFO) && prev.sa_sigaction != nullptr) {
            prev.sa_sigaction(sig, info, ctx);
            return;
        }
        if (prev.sa_handler != SIG_DFL && prev.sa_handler != SIG_IGN &&
            prev.sa_handler != nullptr) {
            prev.sa_handler(sig);
            return;
        }
    }
    struct sigaction dfl{};
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);
    raise(sig);
}

static int count_maps_lines(int cap) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char buf[4096];
    int lines = 0;
    while (lines < cap) {
        ssize_t r = read(fd, buf, sizeof(buf));
        if (r <= 0) break;
        for (ssize_t i = 0; i < r; i++) if (buf[i] == '\n') lines++;
    }
    close(fd);
    return lines;
}

static int count_dir_entries(const char* path, int cap) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -1;

    char buf[4096];
    int n = 0;
    while (n < cap) {
        long r = syscall(SYS_getdents64, fd, buf, sizeof(buf));
        if (r <= 0) break;
        for (long off = 0; off < r;) {
            unsigned short reclen;
            memcpy(&reclen, buf + off + 16, sizeof(reclen));
            if (reclen == 0) { off = r; break; }
            const char* name = buf + off + 19;
            if (!(name[0] == '.' && (name[1] == 0 || (name[1] == '.' && name[2] == 0)))) n++;
            off += reclen;
        }
    }
    close(fd);
    return n;
}

static void write_resource_counts(int fd) {
    const int threads = count_dir_entries("/proc/self/task", 1 << 20);
    const int fds = count_dir_entries("/proc/self/fd", 1 << 20);
    const int maps = count_maps_lines(70000);
    char line[256];
    int n = snprintf(line, sizeof(line),
        "\nResources at crash: threads=%d maps=%d%s fds=%d\n"
        "(maps near 65530 or threads in the thousands = the process ran out of\n"
        "mappings / threads; threads per name: exitinfo-*.log \"Tombstone summary\")\n",
        threads, maps, maps >= 70000 ? "+" : "", fds);
    if (n > 0) write(fd, line, n < (int)sizeof(line) ? n : (int)sizeof(line) - 1);
}

static void crash_signal_handler(int sig, siginfo_t* info, void* ctx) {

    if (g_in_handler) {
        chain_to_previous(sig, info, ctx);
        return;
    }
    g_in_handler = 1;

    if (g_log_dir[0] == 0) {
        chain_to_previous(sig, info, ctx);
        return;
    }

    time_t now = time(nullptr);
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);

    char path[640];
    snprintf(path, sizeof(path),
        "%s/native-crash-%04d-%02d-%02d_%02d-%02d-%02d.log",
        g_log_dir,
        tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
        tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        chain_to_previous(sig, info, ctx);
        return;
    }

    const int tid = (int)syscall(SYS_gettid);

    char comm[64] = {0};
    {
        char comm_path[64];
        snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);
        int cfd = open(comm_path, O_RDONLY);

        if (cfd < 0) {
            cfd = open("/proc/self/comm", O_RDONLY);
            if (cfd >= 0) strncpy(comm, "(main?) ", sizeof(comm) - 1);
        }
        if (cfd >= 0) {
            const size_t used = strlen(comm);
            ssize_t r = read(cfd, comm + used, sizeof(comm) - used - 1);
            if (r > 0) {
                comm[used + r] = 0;
                for (size_t i = 0; i < used + (size_t)r; i++) {
                    if (comm[i] == '\n') comm[i] = 0;
                }
            }
            close(cfd);
        }
    }
    if (comm[0] == 0) strncpy(comm, "(unknown)", sizeof(comm) - 1);

    const int code = info ? info->si_code : 0;

    const bool addr_is_fault = !(code == 0 || code == -1 || code == -6);

    char buf[1600];
    int n;
    if (addr_is_fault) {
        n = snprintf(buf, sizeof(buf),
            "=== Minis Native Crash ===\n"
            "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
            "Signal: %d (%s)\n"
            "si_code: %d\n"
            "Fault addr: %p\n"
            "PID: %d  TID: %d  Thread: %s\n"
            "\n"
            "(Tombstone with full backtrace written by Android system to "
            "/data/tombstones/ — adb pull or `adb bugreport`.)\n",
            tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
            tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec,
            sig, signal_name(sig), code,
            info ? info->si_addr : nullptr,
            getpid(), tid, comm);
    } else {
        n = snprintf(buf, sizeof(buf),
            "=== Minis Native Crash ===\n"
            "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
            "Signal: %d (%s)\n"
            "si_code: %d (%s)\n"
            "Sent by: pid=%d uid=%d%s\n"
            "Fault addr: n/a (not a fault signal — see note)\n"
            "PID: %d  TID: %d  Thread: %s\n"
            "\n"
            "NOTE: this signal was SENT, not raised by a memory fault, so\n"
            "si_addr carries no address. SIGABRT with si_code=-1 and\n"
            "sender pid == our own pid is the ordinary signature of abort()\n"
            "inside this process (libc assertion, Scudo heap check, ART\n"
            "runtime abort, or a C++ uncaught exception).\n"
            "(Tombstone with full backtrace written by Android system to "
            "/data/tombstones/ — adb pull or `adb bugreport`.)\n",
            tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
            tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec,
            sig, signal_name(sig),
            code,
            code == 0 ? "SI_USER" : (code == -1 ? "SI_QUEUE/abort()" : "SI_TKILL"),
            info ? info->si_pid : 0, info ? (int)info->si_uid : 0,
            (info && info->si_pid == getpid()) ? " (this process — self-abort)" : "",
            getpid(), tid, comm);
    }
    if (n > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(fd, buf + written, n - written);
            if (w <= 0) break;
            written += w;
        }
    }

    write_resource_counts(fd);

    if (!addr_is_fault) {
        const char* amsg = abort_message_or_null();
        if (amsg != nullptr) {
            const char* h = "\nAbort message: ";
            write(fd, h, strlen(h));

            size_t len = 0;
            while (len < 4096 && amsg[len] != '\0') len++;
            write(fd, amsg, len);
            write(fd, "\n", 1);
        } else {

            const char* note =
                "\nAbort message: (none captured)\n"
                "The REASON is not in siginfo and no bionic abort message was\n"
                "set — a runtime that aborts on its own (e.g. the Go runtime)\n"
                "leaves none. Look for it in:\n"
                "  * logcat around this timestamp, tags: scudo / libc / DEBUG\n"
                "    (Scudo prints e.g. \"Scudo ERROR: invalid chunk state\")\n"
                "  * the tombstone's \"Abort message:\" line\n"
                "  * exitinfo-*.log in this directory (ApplicationExitInfo\n"
                "    carries the description and the full tombstone)\n";
            write(fd, note, strlen(note));
        }
    }

    write_backtrace(fd);
    close(fd);

    chain_to_previous(sig, info, ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openminis_app_crash_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject, jstring jLogDir) {
    if (jLogDir == nullptr) return;
    const char* dir = env->GetStringUTFChars(jLogDir, nullptr);
    if (dir == nullptr) return;
    strncpy(g_log_dir, dir, sizeof(g_log_dir) - 1);
    g_log_dir[sizeof(g_log_dir) - 1] = 0;
    env->ReleaseStringUTFChars(jLogDir, dir);

    mkdir(g_log_dir, 0755);

    using set_aborter_fn = void (*)(void (*)(const char*));
    if (auto set_aborter =
            reinterpret_cast<set_aborter_fn>(dlsym(RTLD_DEFAULT, "__android_log_set_aborter"))) {
        set_aborter(minis_aborter);
    }

    struct sigaction sa{};
    sa.sa_sigaction = crash_signal_handler;

    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    static const int kSignals[] = {
        SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSYS,
    };
    for (size_t i = 0; i < sizeof(kSignals) / sizeof(kSignals[0]); i++) {
        const int s = kSignals[i];
        struct sigaction old{};
        if (sigaction(s, &sa, &old) == 0) {
            g_prev[s] = old;
            g_prev_valid[s] = 1;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "installed: dir=%s (chaining to prior handlers)", g_log_dir);
}
