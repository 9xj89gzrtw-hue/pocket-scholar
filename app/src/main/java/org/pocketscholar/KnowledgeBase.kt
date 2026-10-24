package org.pocketscholar

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln

/**
 * Offline BM25 index over the bundled knowledge pack.
 *
 * The pack is one JSON object per line: {"title", "description", "extract"}.
 * The index is an inverted list of (docId, termFrequency) pairs, built in memory
 * at launch. No database, no network, no dependencies outside the framework.
 */
class KnowledgeBase(private val context: Context) {

    data class Doc(val title: String, val text: String, val description: String)

    private val docs = ArrayList<Doc>()
    private val lengths = ArrayList<Int>()
    private val postings = HashMap<String, ArrayList<Int>>()
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
        // The title is the strongest signal in a one-paragraph encyclopedia:
        // counting it three times keeps "Boston" out of a question about the
        // Great Depression.
        val tokens = List(3) { tokenize(title) }.flatten() + tokenize("$description $text").take(180)
        if (tokens.isEmpty()) return

        val docId = docs.size
        docs.add(Doc(title, text, description))
        lengths.add(tokens.size)

        val frequencies = HashMap<String, Int>()
        for (token in tokens) frequencies[token] = (frequencies[token] ?: 0) + 1
        for ((token, frequency) in frequencies) {
            val list = postings.getOrPut(token) { ArrayList() }
            list.add(docId)
            list.add(frequency)
        }
    }

    /** BM25 top-k. Returns an empty list when nothing in the pack matches. */
    fun search(query: String, k: Int = 4): List<Doc> {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || docs.isEmpty()) return emptyList()
        val total = docs.size
        val scores = HashMap<Int, Double>()

        for (term in terms) {
            val list = postings[term] ?: continue
            val documentFrequency = list.size / 2
            val idf = ln(1.0 + (total - documentFrequency + 0.5) / (documentFrequency + 0.5))
            var index = 0
            while (index < list.size) {
                val docId = list[index]
                val frequency = list[index + 1]
                val denominator = frequency + 1.2 * (1 - 0.75 + 0.75 * lengths[docId] / averageLength)
                val contribution = idf * frequency * 2.2 / denominator
                scores[docId] = (scores[docId] ?: 0.0) + contribution
                index += 2
            }
        }
        if (scores.isEmpty()) return emptyList()
        return scores.entries
            .sortedByDescending { it.value }
            .take(k)
            .map { docs[it.key] }
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
