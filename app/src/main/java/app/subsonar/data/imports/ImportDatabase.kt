package app.subsonar.data.imports

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.subsonar.data.Converters

/**
 * Imported bank history lives in its own database file so that it can be left out of Android cloud backup
 * and device transfer (see res/xml/backup_rules.xml and data_extraction_rules.xml), while the subscriptions
 * database stays backed up. Schema is exported to app/schemas; add a Migration when changing entities.
 */
@Database(entities = [ImportedTransaction::class, ImportRecord::class, FormatFingerprint::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ImportDatabase : RoomDatabase() {
    abstract fun importDao(): ImportDao

    companion object {
        const val NAME = "history.db"

        fun create(context: Context): ImportDatabase =
            Room.databaseBuilder(context.applicationContext, ImportDatabase::class.java, NAME).build()
    }
}
