package com.pdfreader.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.room.*
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Entity(tableName = "definitions")
data class DefinitionEntity(
    @PrimaryKey val word: String,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "definition") val definition: String,
    @ColumnInfo(name = "examples") val examplesJson: String?, // JSON array string
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface DefinitionDao {
    @Query("SELECT * FROM definitions WHERE word = :word AND language = :language LIMIT 1")
    suspend fun getDefinition(word: String, language: String): DefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDefinition(definition: DefinitionEntity)

    @Query("DELETE FROM definitions WHERE word = :word AND language = :language")
    suspend fun deleteDefinition(word: String, language: String)

    @Query("DELETE FROM definitions WHERE timestamp < :olderThan")
    suspend fun deleteOldDefinitions(olderThan: Long)
}

@Database(
    entities = [DefinitionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DictionaryDatabase : RoomDatabase() {
    abstract fun definitionDao(): DefinitionDao
}

class DictionaryRepository(private val context: Context) {
    private val database: DictionaryDatabase by lazy {
        Room.databaseBuilder(
            context,
            DictionaryDatabase::class.java,
            "dictionary_database"
        ).build()
    }

    suspend fun getDefinition(word: String, language: String): DefinitionResult? {
        return withContext(Dispatchers.IO) {
            val normalizedWord = word.trim().lowercase()
            if (normalizedWord.isBlank()) return@withContext null

            // First, try to get from cache
            val cached = database.definitionDao().getDefinition(normalizedWord, language)
            if (cached != null) {
                return@withContext DefinitionResult(
                    definition = cached.definition,
                    examples = cached.examplesJson?.let { parseExamplesJson(it) }
                )
            }

            // If not in cache, fetch from API
            val apiResult = fetchDefinitionFromApi(normalizedWord, language)
            if (apiResult != null) {
                // Cache the result
                val examplesJson = apiResult.examples?.let { convertExamplesToJson(it) }
                val entity = DefinitionEntity(
                    word = normalizedWord,
                    language = language,
                    definition = apiResult.definition,
                    examplesJson = examplesJson
                )
                database.definitionDao().insertDefinition(entity)
            }
            apiResult
        }
    }

    private suspend fun fetchDefinitionFromApi(word: String, language: String): DefinitionResult? {
        return withContext(Dispatchers.IO) {
            try {
                if (!isNetworkAvailable()) return@withContext null

                when {
                    language.contains("pt", ignoreCase = true) -> fetchFromPortugueseApi(word)
                    else -> fetchFromEnglishApi(word)
                }
            } catch (e: IOException) {
                // Network error occurred
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun fetchFromPortugueseApi(word: String): DefinitionResult? {
        val slug = word
            .trim()
            .lowercase()
            .trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']', '{', '}')

        if (slug.isBlank()) return null

        val url = URL("https://www.dicio.com.br/${Uri.encode(slug)}/")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PdfReader/1.0")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null

            val html = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            val definition = extractDicioDefinition(html) ?: return null
            val examples = extractDicioExamples(html)

            DefinitionResult(
                definition = definition,
                examples = examples
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchFromEnglishApi(word: String): DefinitionResult? {
        return DefinitionResult(
            definition = "Definition for the word '$word' in English",
            examples = listOf("Example 1: $word is used in the sentence...", "Example 2: Another sentence with $word.")
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun parseExamplesJson(jsonString: String): List<String>? {
        if (jsonString.isBlank()) return emptyList()
        return jsonString.split("\u0001").filter { it.isNotBlank() }
    }

    private fun convertExamplesToJson(examples: List<String>): String {
        return examples.joinToString("\u0001")
    }

    private fun extractDicioDefinition(html: String): String? {
        // Primeira tentativa: extrair usando a div com a classe que contém a definição
        val definitionPattern = Regex("""<p[^>]*class="[^"]*significado-texto[^"]*"[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        var match = definitionPattern.find(html)
        if (match != null) {
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (content.isNotBlank()) {
                val cleanContent = cleanupHtml(content)
                return cleanContent
            }
        }

        // Segunda tentativa: usar a meta description
        val patterns = listOf(
            Regex("<meta\\s+name=[\"']description[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("<meta\\s+property=[\"']og:description[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            match = pattern.find(html) ?: continue
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val marker = "O que é "
            val idx = content.indexOf(marker)
            if (idx >= 0) {
                return content.substring(idx + marker.length).trim().trimEnd('.')
            }
            if (content.isNotBlank()) {
                return content.trim().trimEnd('.')
            }
        }

        // Terceira tentativa: buscar o significado em uma div específica
        val meaningPattern = Regex("""<div[^>]*class="[^"]*significado[^"]*"[^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        match = meaningPattern.find(html)
        if (match != null) {
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (content.isNotBlank()) {
                val cleanContent = cleanupHtml(content)
                if (!cleanContent.startsWith("<")) { // Certificar-se de que não é apenas uma tag
                    return cleanContent
                }
            }
        }

        return null
    }

    private fun cleanupHtml(text: String): String {
        val cleaned = text
            .replace(Regex("<[^>]+>"), " ") // Remove todas as tags HTML
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace(Regex("\\s+"), " ") // Substitui múltiplos espaços por um único
            .trim()
        return cleaned
    }

    private fun extractDicioExamples(html: String): List<String>? {
        val examples = mutableListOf<String>()
        
        // Procurar por exemplos em elementos li dentro de seções de exemplos
        val examplePattern = Regex("""<li[^>]*class="[^"]*exemplo[^"]*"[^>]*>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val textPattern = Regex("<[^>]+>")

        for (match in examplePattern.findAll(html)) {
            val raw = match.groupValues.getOrNull(1).orEmpty()
            val text = cleanupHtml(raw)

            if (text.length in 12..140 && !text.contains("Dicio", ignoreCase = true)) {
                examples += text
            }
            if (examples.size >= 3) break
        }

        // Se não encontrou exemplos com a classe específica, tentar com outras abordagens
        if (examples.isEmpty()) {
            val genericExamplePattern = Regex("""<li[^>]*>([^<]*exempl[^<]*)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            
            for (match in genericExamplePattern.findAll(html)) {
                val raw = match.groupValues.getOrNull(1).orEmpty()
                val text = cleanupHtml(raw)

                if (text.length in 12..140 && !text.contains("Dicio", ignoreCase = true)) {
                    examples += text
                }
                if (examples.size >= 3) break
            }
        }

        // Outra tentativa com padrões diferentes
        if (examples.isEmpty()) {
            val altExamplePattern = Regex("""<span[^>]*class="[^"]*exemplo[^"]*"[^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            
            for (match in altExamplePattern.findAll(html)) {
                val raw = match.groupValues.getOrNull(1).orEmpty()
                val text = cleanupHtml(raw)

                if (text.length in 12..140 && !text.contains("Dicio", ignoreCase = true)) {
                    examples += text
                }
                if (examples.size >= 3) break
            }
        }

        return examples.takeIf { it.isNotEmpty() }
    }

    data class DefinitionResult(
        val definition: String,
        val examples: List<String>?
    )
}
