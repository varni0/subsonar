package app.subsonar.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DatabaseMigrationTest {
    private val dbName = "migration-test"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val insert = "INSERT INTO subscriptions (id, name, price, currency_code, cycle, renewal_anchor, category, notes, reminders_enabled, last_reminded_for, created_at) VALUES "

    @Test fun migrate1To2KeepsRowsAndAddsImportKey() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(insert + "(1, 'Netflix', '12.99', 'EUR', 'MONTHLY', 19000, 'STREAMING', 'family', 1, NULL, 1700000000000)")
            execSQL(insert + "(2, 'Gym', '29.00', 'NOK', 'YEARLY', 19100, 'FITNESS', '', 0, 19090, 1700000000001)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT name, price, import_key, last_reminded_for FROM subscriptions ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst(); assertEquals("Netflix", c.getString(0)); assertEquals("12.99", c.getString(1)); assertTrue(c.isNull(2)); assertTrue(c.isNull(3))
            c.moveToNext(); assertEquals("Gym", c.getString(0)); assertEquals(19090L, c.getLong(3))
        }
        db.execSQL("UPDATE subscriptions SET import_key = 'netflix' WHERE id = 1")
        db.close()
    }

    @Test fun migratedDatabaseOpensWithRoomAndKeepsData() = kotlinx.coroutines.runBlocking {
        helper.createDatabase(dbName, 1).apply { execSQL(insert + "(7, 'Spotify', '9.99', 'EUR', 'MONTHLY', 20000, 'MUSIC', '', 1, NULL, 1)"); close() }
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val room = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        val s = room.subscriptionDao().getById(7)!!
        assertEquals("Spotify", s.name); assertNull(s.importKey)
        room.subscriptionDao().update(s.copy(importKey = "spotify"))
        assertEquals("spotify", room.subscriptionDao().getById(7)!!.importKey)
        room.close()
    }

    private val insertHistory = "INSERT INTO price_history (id, subscription_id, effective_date, old_price, new_price, currency_code, cycle, recorded_at, source) VALUES "
    private val insertV2 = "INSERT INTO subscriptions (id, name, price, currency_code, cycle, renewal_anchor, category, notes, reminders_enabled, last_reminded_for, created_at, import_key) VALUES "

    @Test fun migrate2To3CreatesPriceHistoryAndKeepsSubscriptions() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(insertV2 + "(1, 'Netflix', '12.99', 'EUR', 'MONTHLY', 19000, 'STREAMING', 'family', 1, NULL, 1700000000000, 'netflix')")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)
        db.query("SELECT name, price, import_key FROM subscriptions").use { c ->
            assertEquals(1, c.count); c.moveToFirst(); assertEquals("Netflix", c.getString(0)); assertEquals("12.99", c.getString(1)); assertEquals("netflix", c.getString(2))
        }
        db.query("SELECT COUNT(*) FROM price_history").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.execSQL(insertHistory + "(1, 1, 20000, '11.99', '12.99', 'EUR', 'MONTHLY', 1, 'MANUAL')")
        db.query("SELECT old_price, new_price FROM price_history").use { c -> c.moveToFirst(); assertEquals("11.99", c.getString(0)); assertEquals("12.99", c.getString(1)) }
        db.close()
    }

    @Test fun migrate1To3ThroughBothSteps() {
        helper.createDatabase(dbName, 1).apply { execSQL(insert + "(3, 'Gym', '29.00', 'NOK', 'YEARLY', 19100, 'FITNESS', '', 0, NULL, 1)"); close() }
        val db = helper.runMigrationsAndValidate(dbName, 3, true, *AppDatabase.ALL_MIGRATIONS)
        db.query("SELECT name FROM subscriptions").use { c -> assertEquals(1, c.count) }
        db.close()
    }

    @Test fun priceHistoryIsDeletedWithItsSubscription() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(insertV2 + "(1, 'A', '1', 'EUR', 'MONTHLY', 19000, 'OTHER', '', 1, NULL, 1, NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(insertHistory + "(1, 1, 20000, '1', '2', 'EUR', 'MONTHLY', 1, 'MANUAL')")
        db.execSQL("DELETE FROM subscriptions WHERE id = 1")
        db.query("SELECT COUNT(*) FROM price_history").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    @Test fun migratedDatabaseOpensWithRoomAndStoresHistory() = kotlinx.coroutines.runBlocking {
        helper.createDatabase(dbName, 1).apply { execSQL(insert + "(7, 'Spotify', '9.99', 'EUR', 'MONTHLY', 20000, 'MUSIC', '', 1, NULL, 1)"); close() }
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val room = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        val dao = room.subscriptionDao()
        dao.insertPriceChange(PriceChange(subscriptionId = 7, effectiveDate = java.time.LocalDate.of(2026, 3, 1), oldPrice = java.math.BigDecimal("9.99"), newPrice = java.math.BigDecimal("10.99"), currencyCode = "EUR", cycle = BillingCycle.MONTHLY, source = PriceChangeSource.IMPORT))
        val h = dao.getHistory(7).single()
        assertEquals(java.math.BigDecimal("10.99"), h.newPrice); assertEquals(PriceChangeSource.IMPORT, h.source); assertEquals(java.time.LocalDate.of(2026, 3, 1), h.effectiveDate)
        dao.delete(dao.getById(7)!!)
        assertTrue(dao.getHistory(7).isEmpty())
        room.close()
    }

    @Test fun schemasAreExportedForEveryVersion() {
        for (v in 1..4) assertTrue("schema $v", java.io.File("schemas/app.subsonar.data.AppDatabase/$v.json").exists())
        java.io.File("schemas/app.subsonar.data.AppDatabase/4.json").readText().let { assertTrue(it.contains("color_key") && it.contains("\"avatar\"")) }
        assertTrue(java.io.File("schemas/app.subsonar.data.AppDatabase/3.json").readText().contains("price_history"))
    }

    @Test fun importDatabaseSchemaIsExported() {
        assertTrue(java.io.File("schemas/app.subsonar.data.imports.ImportDatabase/1.json").exists())
    }

    @Test fun migrate3To4AddsVisualColumnsAsAutomatic() {
        helper.createDatabase(dbName, 3).apply {
            execSQL(insertV2 + "(1, 'Netflix', '12.99', 'EUR', 'MONTHLY', 19000, 'STREAMING', 'family', 1, NULL, 1700000000000, 'netflix')")
            execSQL(insertHistory + "(1, 1, 20000, '11.99', '12.99', 'EUR', 'MONTHLY', 1, 'MANUAL')")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)
        db.query("SELECT name, import_key, color_key, avatar FROM subscriptions").use { c ->
            assertEquals(1, c.count); c.moveToFirst()
            assertEquals("Netflix", c.getString(0)); assertEquals("netflix", c.getString(1)); assertTrue(c.isNull(2)); assertTrue(c.isNull(3))
        }
        db.query("SELECT COUNT(*) FROM price_history").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.execSQL("UPDATE subscriptions SET color_key = 'moss', avatar = 'icon:gym' WHERE id = 1")
        db.close()
    }

    @Test fun migrate1To4AndRoomStoresVisuals() = kotlinx.coroutines.runBlocking {
        helper.createDatabase(dbName, 1).apply { execSQL(insert + "(5, 'Gym', '29.00', 'NOK', 'MONTHLY', 20000, 'FITNESS', '', 1, NULL, 1)"); close() }
        helper.runMigrationsAndValidate(dbName, 4, true, *AppDatabase.ALL_MIGRATIONS).close()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val room = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        val dao = room.subscriptionDao()
        val s = dao.getById(5)!!
        assertNull(s.colorKey); assertNull(s.avatar)
        dao.update(s.copy(colorKey = "aurora", avatar = "emoji:🏋️"))
        dao.getById(5)!!.let { assertEquals("aurora", it.colorKey); assertEquals("emoji:🏋️", it.avatar) }
        room.close()
    }
}
