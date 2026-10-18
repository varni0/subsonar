package app.subsonar.reminders

import app.subsonar.data.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PriceAlertPolicyTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val on = AppSettings(remindersEnabled = true, priceAlertsEnabled = true)
    private fun ok(s: AppSettings = on, from: String = "10", to: String = "12", date: LocalDate = today.minusDays(5)) =
        PriceAlertPolicy.shouldNotify(s, BigDecimal(from), BigDecimal(to), date, today)

    @Test fun notifiesForRecentIncreases() { assertTrue(ok()); assertTrue(ok(date = today)); assertTrue(ok(date = today.minusDays(60))) }
    @Test fun staysQuietForOldChanges() { assertFalse(ok(date = today.minusDays(61))) }
    @Test fun staysQuietForDecreasesAndNoChange() { assertFalse(ok(from = "12", to = "10")); assertFalse(ok(from = "12", to = "12.00")) }
    @Test fun respectsBothNotificationSettings() {
        assertFalse(ok(on.copy(remindersEnabled = false)))
        assertFalse(ok(on.copy(priceAlertsEnabled = false)))
    }
    @Test fun futureEffectiveDateIsNotNews() { assertFalse(ok(date = today.plusDays(3))) }
}
