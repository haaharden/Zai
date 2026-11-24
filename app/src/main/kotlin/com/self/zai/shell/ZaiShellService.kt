package com.self.zai.shell

import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService executing shell commands with UID 2000 (ADB) or UID 0 (Root).
 */
class ZaiShellService : IZaiShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun getUid(): Int = Process.myUid()

    override fun exec(command: String?, timeoutMs: Int): String {
        if (command.isNullOrBlank()) return ""
        val boundedTimeout = timeoutMs.coerceIn(1_000, 30_000).toLong()
        return try {
            val proc = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val out = ByteArrayOutputStream()
            val readerThread = Thread {
                runCatching {
                    val buf = ByteArray(4096)
                    val input = proc.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() < MAX_OUTPUT_BYTES) {
                            out.write(buf, 0, n.coerceAtMost(MAX_OUTPUT_BYTES - out.size()))
                        }
                    }
                }
            }.apply { start() }

            val finished = proc.waitFor(boundedTimeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                readerThread.join(500)
                return "ERR_TIMEOUT: ${out.toString(Charsets.UTF_8.name())}"
            }
            readerThread.join(1000)
            val exitCode = proc.exitValue()
            val text = out.toString(Charsets.UTF_8.name())
            if (exitCode == 0) text else "ERR_EXIT_$exitCode: $text"
        } catch (t: Throwable) {
            "ERR_EXCEPTION: ${t.message}"
        }
    }

    override fun screencapPng(): ByteArray {
        return try {
            val proc = ProcessBuilder("screencap", "-p").start()
            val bytes = proc.inputStream.readBytes()
            proc.waitFor(5_000, TimeUnit.MILLISECONDS)
            if (bytes.size <= MAX_BINDER_BYTES) bytes else ByteArray(0)
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 512 * 1024
        private const val MAX_BINDER_BYTES = 750 * 1024
    }
}
