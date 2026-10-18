package app.subsonar

import android.content.Context
import app.subsonar.data.AppDatabase
import app.subsonar.data.SettingsRepository
import app.subsonar.data.imports.ImportDatabase
import app.subsonar.data.imports.ImportRepository
import app.subsonar.data.SubscriptionRepository
import app.subsonar.premium.Entitlements
import app.subsonar.premium.PremiumManager

/** Minimal manual dependency injection; easy to migrate to Hilt later if the app grows. */
class AppContainer(context: Context) {
    private val database = AppDatabase.create(context)
    val subscriptions = SubscriptionRepository(database.subscriptionDao(), database)
    private val importDatabase = ImportDatabase.create(context)
    val imports = ImportRepository(importDatabase)
    val settings = SettingsRepository(context)
    val premiumManager = PremiumManager()
    val entitlements: Entitlements get() = premiumManager
}
