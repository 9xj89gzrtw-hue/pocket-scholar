package org.pocketscholar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var knowledge: KnowledgeBase

    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var askButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var answer: TextView
    private lateinit var sources: TextView

    private var articleCount = 0
    private var corpusLoaded = false
    private var busy = false
    private var pendingQuery: String? = null
    private lateinit var scroll: ScrollView
    private lateinit var samplesBlock: LinearLayout

    private val samples = listOf(
        "What caused the collapse of the Bronze Age?",
        "Compare photosynthesis and cellular respiration",
        "Why did the Roman Empire fall?"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        knowledge = KnowledgeBase(this)
        setContentView(buildUi())
        refreshStatus()

        worker.execute {
            articleCount = knowledge.load()
            corpusLoaded = true
            runOnUiThread {
                refreshStatus()
                maybeRunPending()
            }
        }

        if (intent?.getBooleanExtra("autorun", false) == true) {
            val question = intent.queryExtra()
            if (!question.isNullOrBlank()) {
                input.setText(question)
                pendingQuery = question
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val question = intent?.queryExtra() ?: return
        input.setText(question)
        pendingQuery = question
        maybeRunPending()
    }

    /*
     * A second `am start` can arrive while the previous question is still being
     * answered (the CI demo fires one every few minutes). The pending question is
     * kept and started as soon as the worker is free, instead of being dropped.
     */

    /** `adb shell am start` splits on spaces, so CI passes "why+did+..." instead. */
    private fun Intent.queryExtra(): String? =
        getStringExtra("query")?.replace('+', ' ')

    /** Autorun from CI: wait until both the corpus and a model are ready. */
    private fun maybeRunPending() {
        val question = pendingQuery ?: return
        if (busy || !corpusLoaded || LlamaCli.model(this) == null) return
        pendingQuery = null
        input.setText(question)
        ask()
    }

    // ---------------------------------------------------------------- UI ----

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.WHITE)
        }

        column.addView(TextView(this).apply {
            text = "Pocket Scholar"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
        })

        column.addView(TextView(this).apply {
            text = "Offline research assistant · answers from a local model and a local encyclopedia"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(4), 0, dp(14))
        })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#33691E"))
            setPadding(0, 0, 0, dp(12))
        }
        column.addView(status)

        input = EditText(this).apply {
            hint = "Ask a research question…"
            textSize = 16f
            setSingleLine(false)
            minLines = 2
            setTextColor(Color.parseColor("#111111"))
        }
        column.addView(input)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        askButton = Button(this).apply {
            text = "Ask"
            setOnClickListener { ask() }
        }
        buttons.addView(askButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Model…"
            setOnClickListener { pickModel() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(buttons)

        samplesBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        samplesBlock.addView(TextView(this).apply {
            text = "Try one of these:"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(4), 0, dp(2))
        })
        for (sample in samples) {
            samplesBlock.addView(Button(this).apply {
                text = sample
                textSize = 12f
                isAllCaps = false
                setOnClickListener { input.setText(sample); ask() }
            })
        }
        column.addView(samplesBlock)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(10))
        }
        column.addView(progress)

        answer = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#111111"))
            setPadding(0, dp(12), 0, dp(10))
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        column.addView(answer)

        sources = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, dp(6), 0, dp(20))
        }
        column.addView(sources)

        return ScrollView(this).apply {
            scroll = this
            addView(column)
        }
    }

    private fun refreshStatus() {
        val model = LlamaCli.model(this)
        val corpus = if (articleCount > 0) "$articleCount articles" else "loading corpus…"
        val modelText = if (model != null) String.format("model %.0f MB", model.length() / 1e6)
        else "no model — tap “Model…” to import a .gguf"
        status.text = "Offline · $corpus · $modelText"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- actions --

    private fun ask() {
        if (busy) return
        val question = input.text.toString().trim()
        if (question.isEmpty()) {
            Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
            return
        }
        val model = LlamaCli.model(this)
        if (model == null) {
            Toast.makeText(this, "Import a model first (Model… button)", Toast.LENGTH_LONG).show()
            return
        }

        busy = true
        askButton.isEnabled = false
        progress.visibility = View.VISIBLE
        answer.text = ""
        sources.text = ""

        worker.execute {
            try {
                val passages = if (articleCount > 0) knowledge.search(question, 4) else emptyList()
                val prompt = buildPrompt(question, passages)
                val started = System.currentTimeMillis()
                val text = LlamaCli.generate(this, prompt)
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                runOnUiThread {
                    answer.text = text.ifBlank { "(the model returned no text)" }
                    sources.text = if (passages.isEmpty())
                        "No matching articles — answered from the model's own knowledge. ${String.format("%.1f s", seconds)}"
                    else
                        "Sources: " + passages.mapIndexed { i, d -> "[${i + 1}] ${d.title}" }.joinToString(" · ") +
                            "   (${String.format("%.1f s", seconds)})"
                    // the question, the answer and its sources should share the screen:
                    // the sample buttons have done their job by now
                    samplesBlock.visibility = View.GONE
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (error: Exception) {
                runOnUiThread { answer.text = "Error: ${error.message}" }
            } finally {
                runOnUiThread {
                    busy = false
                    askButton.isEnabled = true
                    progress.visibility = View.GONE
                    maybeRunPending()
                }
            }
        }
    }

    private fun buildPrompt(question: String, passages: List<KnowledgeBase.Doc>): String {
        val system = "You are Pocket Scholar, an offline research assistant on an Android phone. " +
            "Answer from the reference passages and name the passage each point comes from, as [1]. " +
            "Start with a one-sentence answer, then give two or three concrete details. If the " +
            "question compares two things, say explicitly what each one does that the other does " +
            "not. If the passages do not cover the question, say so and mark anything you add from " +
            "your own knowledge. Never invent a citation."
        val context = passages.mapIndexed { index, doc ->
            "[${index + 1}] ${doc.title}: " + doc.text.replace("\n", " ").take(800)
        }.joinToString("\n\n")
        return buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            append("<|im_start|>user\n")
            if (context.isNotEmpty()) append("Reference passages:\n").append(context).append("\n\n")
            append("Question: ").append(question)
            append("\n\nAnswer in at most 90 words.<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    private fun pickModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_MODEL)
    }

    @Deprecated("Activity result API requires AndroidX; this app deliberately has no dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        copyModel(uri)
    }

    private fun copyModel(uri: Uri) {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                val target = File(filesDir, "model.gguf")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "cannot open the selected file" }
                    target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
                runOnUiThread {
                    refreshStatus()
                    Toast.makeText(this, "Model imported", Toast.LENGTH_SHORT).show()
                    maybeRunPending()
                }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "Import failed: ${error.message}", Toast.LENGTH_LONG).show() }
            } finally {
                runOnUiThread { progress.visibility = View.GONE }
            }
        }
    }

    companion object {
        private const val REQUEST_MODEL = 42
    }
}
