package org.pocketscholar

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln

/**
 * Offline BM25 index over the bundled knowledge pack.
 *
 * The pack is one JSON object per line: {"title", "description", "extract"}.
 * Everything is built in memory at startup; there is no database, no network,
 * and no dependency outside the Android framework.
 */
class KnowledgeBase(private val context: Context) {

    data class Doc(val title: String, val text: String, val description: String)

    private val docs = ArrayList<Doc>()
    private val termFreqs = ArrayList<HashMap<String, Int>>()
    private val lengths = ArrayList<Int>()
    private val docFrequency = HashMap<String, Int>()
    private var averageLength = 1.0

    fun size(): Int = docs.size

    fun load(): Int {
        val started = System.currentTimeMillis()
        context.assets.open("knowledge/pack.jsonl").bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                index(line)
                line = reader.readLine()
            }
        }
        averageLength = if (lengths.isEmpty()) 1.0 else lengths.average()
        android.util.Log.i("PocketScholar",
            "loaded ${docs.size} articles in ${System.currentTimeMillis() - started} ms")
        return docs.size
    }

    private fun index(line: String) {
        if (line.isBlank()) return
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        val title = json.optString("title")
        val text = json.optString("extract")
        if (title.isBlank() || text.isBlank()) return
        val description = json.optString("description")
        val tokens = tokenize("$title $description $text").take(220)
        if (tokens.isEmpty()) return
        docs.add(Doc(title, text, description))
        val tf = HashMap<String, Int>()
        for (token in tokens) tf[token] = (tf[token] ?: 0) + 1
        termFreqs.add(tf)
        lengths.add(tokens.size)
        for (token in tf.keys) docFrequency[token] = (docFrequency[token] ?: 0) + 1
    }

    /** BM25 top-k. Returns an empty list when nothing in the pack matches. */
    fun search(query: String, k: Int = 4): List<Doc> {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || docs.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Double>>()
        for (index in docs.indices) {
            var score = 0.0
            val tf = termFreqs[index]
            val length = lengths[index]
            for (term in terms) {
                val frequency = tf[term] ?: continue
                val df = docFrequency[term] ?: continue
                val idf = ln(1.0 + (docs.size - df + 0.5) / (df + 0.5))
                val denominator = frequency + 1.2 * (1 - 0.75 + 0.75 * length / averageLength)
                score += idf * frequency * 2.2 / denominator
            }
            if (score > 0.0) scored.add(index to score)
        }
        return scored.sortedByDescending { it.second }.take(k).map { docs[it.first] }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOPWORDS }

    companion object {
        private val STOPWORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her",
            "was", "one", "our", "out", "has", "him", "his", "how", "its", "who", "did", "yes",
            "what", "when", "where", "which", "with", "this", "that", "from", "they", "have",
            "were", "been", "into", "than", "then", "them", "some", "such", "only", "also",
            "about", "would", "there", "their", "these", "those", "does", "most", "more"
        )
    }
}
