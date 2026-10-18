package app.subsonar.ui

import androidx.annotation.StringRes
import app.subsonar.R
import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import android.text.format.DateFormat
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

fun formatMoney(amount: BigDecimal, currencyCode: String, locale: Locale = Locale.getDefault()): String =
    try {
        val currency = Currency.getInstance(currencyCode)
        NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            val digits = currency.defaultFractionDigits.coerceAtLeast(0)
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(amount)
    } catch (_: IllegalArgumentException) {
        "${amount.toPlainString()} $currencyCode"
    }

/** "+16.7%" / "16.7%" in the number style of [locale] (e.g. "+16,7 %" in German). [percent] is already in percent (16.7, not 0.167). */
fun formatPercent(percent: BigDecimal, locale: Locale = Locale.getDefault(), signed: Boolean = false): String {
    val nf = NumberFormat.getPercentInstance(locale)
    nf.minimumFractionDigits = 0
    nf.maximumFractionDigits = 1
    if (signed && nf is DecimalFormat) nf.positivePrefix = "+" + nf.positivePrefix
    return nf.format(percent.divide(BigDecimal(100)))
}

/** Money with an explicit sign, e.g. "+€24.00" or "-€3.00". */
fun formatSignedMoney(amount: BigDecimal, currencyCode: String, locale: Locale = Locale.getDefault()): String {
    val text = formatMoney(amount.abs(), currencyCode, locale)
    return when (amount.signum()) {
        1 -> "+$text"
        -1 -> "\u2212$text"
        else -> text
    }
}

fun formatDateMedium(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** "Mar 2026" in the order and language of [locale] (uses the platform's best pattern for the "yMMM" skeleton). */
fun formatMonthYear(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMM"), locale))

/**
 * Parses user input like "9.99", "9,99", " 120 ", "1,299.00" or "1.299,00". Returns null when invalid or negative.
 * When both separators occur, the last one is the decimal separator. A single separator is always a decimal separator.
 */
fun parsePrice(input: String): BigDecimal? {
    var cleaned = input.trim().replace(Regex("[\\s\u00A0\u202F']"), "")
    val lastComma = cleaned.lastIndexOf(',')
    val lastDot = cleaned.lastIndexOf('.')
    cleaned = if (lastComma >= 0 && lastDot >= 0) {
        if (lastComma > lastDot) cleaned.replace(".", "").replace(',', '.') else cleaned.replace(",", "")
    } else cleaned.replace(',', '.')
    if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1 || !cleaned.all { it.isDigit() || it == '.' }) return null
    val value = cleaned.toBigDecimalOrNull() ?: return null
    return if (value.signum() < 0 || value.scale() > 4) null else value
}

fun isValidCurrency(code: String): Boolean =
    code.length == 3 && runCatching { Currency.getInstance(code) }.isSuccess

@get:StringRes
val BillingCycle.labelRes: Int
    get() = when (this) {
        BillingCycle.WEEKLY -> R.string.cycle_weekly
        BillingCycle.MONTHLY -> R.string.cycle_monthly
        BillingCycle.QUARTERLY -> R.string.cycle_quarterly
        BillingCycle.YEARLY -> R.string.cycle_yearly
    }

@get:StringRes
val Category.labelRes: Int
    get() = when (this) {
        Category.STREAMING -> R.string.cat_streaming
        Category.MUSIC -> R.string.cat_music
        Category.SOFTWARE -> R.string.cat_software
        Category.CLOUD -> R.string.cat_cloud
        Category.GAMING -> R.string.cat_gaming
        Category.NEWS -> R.string.cat_news
        Category.FITNESS -> R.string.cat_fitness
        Category.UTILITIES -> R.string.cat_utilities
        Category.PHONE_INTERNET -> R.string.cat_phone_internet
        Category.INSURANCE -> R.string.cat_insurance
        Category.EDUCATION -> R.string.cat_education
        Category.OTHER -> R.string.cat_other
    }
