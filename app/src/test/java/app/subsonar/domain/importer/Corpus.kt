package app.subsonar.domain.importer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Locates the CSV sample corpus: permissive/own files from test resources, GPL files only from an external dir. */
object Corpus {
    private val resourceRoot: File by lazy {
        File(Corpus::class.java.classLoader!!.getResource("csv-samples/expectations.json")!!.toURI()).parentFile
    }
    private val externalRoot: File? by lazy {
        System.getProperty("csvSamples.dir")?.let(::File)?.takeIf { it.isDirectory }
    }

    class Entry(val json: JSONObject) {
        val path: String = json.getString("path")
        val heldOut = json.optBoolean("heldOut")
        val gpl = json.optBoolean("gpl")
        val synthetic = json.optBoolean("synthetic")
        val matrix = json.optBoolean("matrix")
        val delimiter: Char? = json.optString("delimiter", "").firstOrNull()
        val datePattern: String? = json.optString("datePattern", "").ifEmpty { null }
        val decimal: Char? = json.optString("decimal", "").firstOrNull()
        val hasHeader: Boolean? = if (json.has("hasHeader")) json.getBoolean("hasHeader") else null
        val encodingClass: String? = json.optString("encodingClass", "").ifEmpty { null }
        val masked = json.optBoolean("maskedDates")
        val altSplit: List<Int> = ints("altSplit")
        val rowsRange: IntRange? = json.optJSONArray("rowsRange")?.let { it.getInt(0)..it.getInt(1) }
        val rows: Int? = if (json.has("rows")) json.getInt("rows") else null
        val dateCols: List<Int> = ints("dateCols")
        val amountCols: List<Int> = ints("amountCols")
        val debitCol: Int? = if (json.has("debitCol")) json.getInt("debitCol") else null
        val creditCol: Int? = if (json.has("creditCol")) json.getInt("creditCol") else null
        val dirCol: Int? = if (json.has("dirCol")) json.getInt("dirCol") else null
        private fun ints(k: String) = json.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()

        /** Bytes of the sample, or null if it is GPL and the external corpus is not available. */
        fun bytes(): ByteArray? {
            val inRes = File(resourceRoot, path)
            if (inRes.exists()) return inRes.readBytes()
            val ext = externalRoot?.let { File(it, path) }
            return ext?.takeIf { it.exists() }?.readBytes()
        }
        override fun toString() = path
    }

    val entries: List<Entry> by lazy {
        val arr = JSONArray(File(resourceRoot, "expectations.json").readText())
        (0 until arr.length()).map { Entry(arr.getJSONObject(it)) }
    }

    fun resource(path: String): ByteArray? = File(resourceRoot, path).takeIf { it.exists() }?.readBytes()
    fun resourceText(path: String): String = File(resourceRoot, path).readText()
    fun resourceFile(path: String): File = File(resourceRoot, path)
}
