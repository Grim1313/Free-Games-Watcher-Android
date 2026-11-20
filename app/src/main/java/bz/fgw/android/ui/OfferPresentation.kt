package bz.fgw.android.ui

import bz.fgw.android.data.StoredOffer
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

internal fun formatOfferPrice(offer: StoredOffer, locale: Locale): String? {
    val currency = runCatching { Currency.getInstance(offer.currency) }.getOrNull()
    val supplied = offer.priceText?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    if (supplied != null) {
        return if (currency != null && !supplied.contains(currency.currencyCode, ignoreCase = true)) {
            "$supplied ${currency.currencyCode}"
        } else supplied
    }
    val amount = offer.originalPrice?.takeIf { it > 0 } ?: return null
    if (currency == null) return null
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)
    // Steam's price API represents every currency in hundredths, including JPY.
    val scale = if (offer.store == "steam") 2 else digits
    val formatter = NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = currency
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
    return "${formatter.format(BigDecimal.valueOf(amount).movePointLeft(scale))} ${currency.currencyCode}"
}
