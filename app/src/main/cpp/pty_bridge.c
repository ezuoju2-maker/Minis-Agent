static void closeInheritedFds(void) {
    if (syscall(__NR_close_range, 3U, ~0U, 0U) == 0) return;
    long maxFd = 65536;
    struct rlimit rl;
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY && rl.rlim_cur < (rlim_t) maxFd) {
        maxFd = (long) rl.rlim_cur;
    }
    for (long fd = 3; fd < maxFd; fd++) {
        syscall(__NR_close, (int) fd);
    }
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_forkExec(
    JNIEnv *env, jclass clazz,
    jstring jCmd, jobjectArray jArgv, jobjectArray jEnvp,
    jstring jCwd, jint cols, jint rows, jintArray outPid)
{
    const char *cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
    if (!cmd) return -EINVAL;

    jsize argc = (*env)->GetArrayLength(env, jArgv);
    char **argv = (char **) calloc(argc + 1, sizeof(char *));
    if (!argv) {
        (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
        return -ENOMEM;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jArgv, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(cs ? cs : "");
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    jsize envc = jEnvp ? (*env)->GetArrayLength(env, jEnvp) : 0;
    char **envp = (char **) calloc(envc + 1, sizeof(char *));
    if (!envp) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
        return -ENOMEM;
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jEnvp, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        envp[i] = strdup(cs ? cs : "");
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    envp[envc] = NULL;

    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    struct winsize ws;
    ws.ws_col = (cols > 0) ? (unsigned short) cols : 80;
    ws.ws_row = (rows > 0) ? (unsigned short) rows : 24;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    struct termios tio;
    memset(&tio, 0, sizeof(tio));
    tio.c_iflag = ICRNL | IXON | IUTF8;
    tio.c_oflag = OPOST | ONLCR;
    tio.c_cflag = CREAD | CS8 | HUPCL;
    tio.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
    cfsetispeed(&tio, B38400);
    cfsetospeed(&tio, B38400);
    tio.c_cc[VINTR]    = 0x03;
    tio.c_cc[VQUIT]    = 0x1c;
    tio.c_cc[VERASE]   = 0x7f;
    tio.c_cc[VKILL]    = 0x15;
    tio.c_cc[VEOF]     = 0x04;
    tio.c_cc[VSTART]   = 0x11;
    tio.c_cc[VSTOP]    = 0x13;
    tio.c_cc[VSUSP]    = 0x1a;
    tio.c_cc[VMIN]     = 1;
    tio.c_cc[VTIME]    = 0;

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, &tio, &ws);
    if (pid < 0) {
        int err = errno;
        LOGE("forkpty failed: %s", strerror(err));
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
        (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
        if (jCwd && cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        return -err;
    }

    if (pid == 0) {
        if (cwd && *cwd) chdir(cwd);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);

        closeInheritedFds();

        execve(cmd, argv, envp);
        _exit(127);
    }

    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(envp);
    (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    if (jCwd && cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);

    jint pidOut = (jint) pid;
    (*env)->SetIntArrayRegion(env, outPid, 0, 1, &pidOut);
    LOGI("forkpty ok: pid=%d masterFd=%d cols=%d rows=%d", pid, masterFd, cols, rows);
    return masterFd;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_readBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len)
{
    if (fd < 0 || len <= 0) return -EINVAL;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!data) return -ENOMEM;
    ssize_t n = read(fd, data + off, (size_t) len);
    int err = (n < 0) ? errno : 0;
    (*env)->ReleaseByteArrayElements(env, buf, data, 0);
    if (n < 0) return -err;
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_writeBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len)
{
    if (fd < 0 || len <= 0) return -EINVAL;
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!data) return -ENOMEM;
    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, data + off + written, (size_t) (len - written));
        if (n < 0) {
            if (errno == EINTR) continue;
            int err = errno;
            (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
            return -err;
        }
        written += n;
    }
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_setWindowSize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows)
{
    if (fd < 0) return -EINVAL;
    struct winsize ws;
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_closeFd(
    JNIEnv *env, jclass clazz, jint fd)
{
    if (fd < 0) return 0;
    if (close(fd) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_sendSignal(
    JNIEnv *env, jclass clazz, jint pid, jint sig)
{
    if (pid <= 0) return -EINVAL;
    if (kill((pid_t) pid, sig) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_waitFor(
    JNIEnv *env, jclass clazz, jint pid)
{
    if (pid <= 0) return -EINVAL;
    int status = 0;
    while (1) {
        pid_t r = waitpid((pid_t) pid, &status, 0);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        break;
    }
    if (WIFEXITED(status)) return (jint) WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (jint) -(128 + WTERMSIG(status));
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_unblockSigpipe(
    JNIEnv *env, jclass clazz)
{
    sigset_t set, old, pending;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    if (sigpending(&pending) == 0 && sigismember(&pending, SIGPIPE) == 1) {
        struct timespec zero = { 0, 0 };
        int drained = 0;
        while (drained < 8 && sigtimedwait(&set, NULL, &zero) == SIGPIPE) drained++;
        __android_log_print(ANDROID_LOG_WARN, "PtyBridge",
            "[T-sigpipe-pending-drain] drained %d pending SIGPIPE before unblock", drained);
    }
    int rc = pthread_sigmask(SIG_UNBLOCK, &set, &old);
    if (rc != 0) return -rc;
    jint result = sigismember(&old, SIGPIPE) == 1 ? 1 : 0;
    struct sigaction sa;
    if (sigaction(SIGPIPE, NULL, &sa) == 0 && sa.sa_handler == SIG_IGN) result |= 2;
    return result;
}

JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_PtyBridge_blockSigpipe(
    JNIEnv *env, jclass clazz)
{
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    int rc = pthread_sigmask(SIG_BLOCK, &set, NULL);
    return rc == 0 ? 0 : -rc;
}
