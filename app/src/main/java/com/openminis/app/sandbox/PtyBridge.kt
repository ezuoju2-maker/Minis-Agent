package com.openminis.app.sandbox

object PtyBridge {
    init {
        System.loadLibrary("pty_bridge")
    }

    @JvmStatic
    external fun forkExec(
        cmd: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
        cols: Int,
        rows: Int,
        outPid: IntArray
    ): Int

    @JvmStatic
    external fun readBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    @JvmStatic
    external fun writeBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    @JvmStatic
    external fun setWindowSize(fd: Int, cols: Int, rows: Int): Int

    @JvmStatic
    external fun closeFd(fd: Int): Int

    @JvmStatic
    external fun sendSignal(pid: Int, sig: Int): Int

    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun unblockSigpipe(): Int

    @JvmStatic
    external fun blockSigpipe(): Int
}
