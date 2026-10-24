package org.pocketscholar

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled llama.cpp completion binary as a subprocess.
 *
 * Why a process instead of JNI: the same binary can be built for every Android
 * ABI by llama.cpp's own CMake, it cannot crash the UI thread, and the app has
 * no native glue to keep in sync with llama.cpp's C API. The binary is shipped
 * as `libllama-completion.so` so Android extracts it with execute permission.
 */
object LlamaCli {

    private const val TAG = "PocketScholar"

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libllama-completion.so")

    /** Model search order: internal files dir, then app-specific external dir. */
    fun model(context: Context): File? = sequenceOf(
        File(context.filesDir, "model.gguf"),
        File(context.getExternalFilesDir(null), "model.gguf")
    ).firstOrNull { it.isFile && it.length() > 0 }

    fun generate(
        context: Context,
        prompt: String,
        maxTokens: Int = 160,
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
            "--temp", "0.2",
            "--top-p", "0.9",
            "--no-display-prompt",
            "--no-conversation"
        )
        Log.i(TAG, "exec ${binary.name} on ${Build.SUPPORTED_ABIS.joinToString(",")} " +
            "model=${model.length() / 1024 / 1024} MB threads=$threads prompt=${prompt.length} chars")
        val started = System.currentTimeMillis()
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
            error("llama-completion exited ${process.exitValue()}: ${stderr.takeLast(600)}")
        }
        val text = clean(stdout.toString())
        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "answered ${text.length} chars in ${elapsed / 1000.0} s " +
            "(${if (elapsed > 0) text.length * 1000L / elapsed else 0} chars/s)")
        return text
    }

    /** llama.cpp writes "[end of text]"-style markers and can trail off mid-sentence. */
    private fun clean(raw: String): String {
        var text = raw
        for (marker in listOf("<|im_end|>", "<|endoftext|>", "[end of text]", "<|im_start|>")) {
            text = text.substringBefore(marker)
        }
        text = text.trim()
        // The token budget stops generation wherever it runs out, so an answer can end in
        // the middle of a sentence. Cut back to the last full sentence instead of showing
        // "... but their cultural traditions".
        val finished = Regex("""[.!?]["')\]]*(?:\s*\[\d+])*\s*$""")
        if (text.isNotEmpty() && !finished.containsMatchIn(text)) {
            val cut = maxOf(text.lastIndexOf('.'), text.lastIndexOf('!'), text.lastIndexOf('?'))
            if (cut > text.length / 3) text = text.substring(0, cut + 1).trim()
        }
        return text
    }
}
