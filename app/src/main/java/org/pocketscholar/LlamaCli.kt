package org.pocketscholar

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled llama.cpp CLI as a subprocess.
 *
 * Why a process instead of JNI: the same binary can be built for every Android
 * ABI by llama.cpp's own CMake, it cannot crash the UI thread, and the app has
 * no native glue to keep in sync with llama.cpp's C API. The binary is shipped
 * as `libllama-cli.so` so Android extracts it with execute permission.
 */
object LlamaCli {

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libllama-cli.so")

    /** Model search order: internal files dir, then app-specific external dir. */
    fun model(context: Context): File? = sequenceOf(
        File(context.filesDir, "model.gguf"),
        File(context.getExternalFilesDir(null), "model.gguf")
    ).firstOrNull { it.isFile && it.length() > 0 }

    fun generate(
        context: Context,
        prompt: String,
        maxTokens: Int = 220,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
        timeoutSeconds: Long = 300
    ): String {
        val model = model(context) ?: error("No model.gguf found in the app storage.")
        val binary = binary(context)
        require(binary.isFile) { "Native binary missing: ${binary.absolutePath}" }

        val command = listOf(
            binary.absolutePath,
            "-m", model.absolutePath,
            "-p", prompt,
            "-n", maxTokens.toString(),
            "-c", "4096",
            "-t", threads.toString(),
            "--temp", "0.25",
            "--top-p", "0.9",
            "--no-display-prompt",
            "--no-conversation"
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .apply {
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            }
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { stdout.appendLine(it) }
            }
        }
        val errorReader = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { stderr.appendLine(it) }
            }
        }
        reader.start(); errorReader.start()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Inference timed out after ${timeoutSeconds}s")
        }
        reader.join(5_000); errorReader.join(5_000)

        if (process.exitValue() != 0) {
            error("llama-cli exited ${process.exitValue()}: ${stderr.takeLast(600)}")
        }
        return stdout.toString().trim()
    }
}
