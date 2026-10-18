package app.subsonar.data

import androidx.room.TypeConverter
import java.math.BigDecimal
import java.time.LocalDate

class Converters {
    @TypeConverter fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()
    @TypeConverter fun stringToBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)

    @TypeConverter fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter fun cycleToString(value: BillingCycle): String = value.name
    @TypeConverter fun stringToCycle(value: String): BillingCycle =
        BillingCycle.entries.firstOrNull { it.name == value } ?: BillingCycle.MONTHLY

    @TypeConverter fun categoryToString(value: Category): String = value.name
    @TypeConverter fun stringToCategory(value: String): Category = Category.fromName(value)

    @TypeConverter fun sourceToString(value: PriceChangeSource): String = value.name
    @TypeConverter fun stringToSource(value: String): PriceChangeSource =
        PriceChangeSource.entries.firstOrNull { it.name == value } ?: PriceChangeSource.MANUAL
}
