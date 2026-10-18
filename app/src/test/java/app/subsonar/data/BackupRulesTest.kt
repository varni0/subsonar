package app.subsonar.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The imported bank history must never be backed up or transferred; subscriptions must stay backed up. */
class BackupRulesTest {
    private fun includes(file: String): List<Pair<String, String>> {
        val f = File("src/main/res/xml/$file").takeIf { it.exists() } ?: File("app/src/main/res/xml/$file")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("include")
        return (0 until nodes.length).map { nodes.item(it).attributes.let { a -> a.getNamedItem("domain").nodeValue to a.getNamedItem("path").nodeValue } }
    }
    private fun noExcludeNeeded(file: String) {
        val inc = includes(file)
        assertTrue(inc.contains("database" to "subsonar.db"))
        assertTrue(inc.none { it.second.startsWith("history") })
        assertTrue(inc.all { it.second == "subsonar.db" || it.second == "datastore/" })
    }
    @Test fun fullBackupContentOmitsHistory() = noExcludeNeeded("backup_rules.xml")
    @Test fun dataExtractionRulesOmitHistoryFromCloudAndTransfer() = noExcludeNeeded("data_extraction_rules.xml")
    @Test fun historyDatabaseHasItsOwnFile() {
        assertEquals("history.db", app.subsonar.data.imports.ImportDatabase.NAME)
        assertNotEquals(AppDatabase.NAME, app.subsonar.data.imports.ImportDatabase.NAME)
    }
}
