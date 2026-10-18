package app.subsonar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema is exported to app/schemas. When changing entities, bump [version] and add a
 * Migration (or AutoMigration) so users never lose data on update.
 */
@Database(entities = [Subscription::class, PriceChange::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        const val NAME = "subsonar.db"
        private const val ADD_IMPORT_KEY = "ALTER TABLE subscriptions ADD COLUMN import_key TEXT"

        /** v2: subscriptions remember which bank-import suggestion created them. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(ADD_IMPORT_KEY)
            }
        }

        /** v3: price history per subscription (table + index; existing rows untouched). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`subscription_id` INTEGER NOT NULL, `effective_date` INTEGER NOT NULL, `old_price` TEXT NOT NULL, " +
                        "`new_price` TEXT NOT NULL, `currency_code` TEXT NOT NULL, `cycle` TEXT NOT NULL, " +
                        "`recorded_at` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_price_history_subscription_id` ON `price_history` (`subscription_id`)")
            }
        }

        /** v4: per-subscription avatar colour and icon/emoji (both nullable = automatic; existing rows keep the smart default). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN color_key TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN avatar TEXT")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
