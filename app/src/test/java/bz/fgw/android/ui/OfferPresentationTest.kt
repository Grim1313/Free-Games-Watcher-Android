package bz.fgw.android.ui

import bz.fgw.android.data.StoredOffer
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class OfferPresentationTest {
    private val offer = StoredOffer("test", "app:123", "Game", "https://example.com", null, null, "US", 0, store = "steam")

    @Test fun steamJapanesePricesUseHundredths() {
        assertEquals("¥1,250 JPY", formatOfferPrice(offer.copy(originalPrice = 125_000, currency = "JPY"), Locale.US))
        assertEquals("$19.99 USD", formatOfferPrice(offer.copy(originalPrice = 1999, currency = "USD"), Locale.US))
    }

    @Test fun otherStoresUseTheirCurrencyPrecision() {
        assertEquals("¥1,250 JPY", formatOfferPrice(offer.copy(store = "epic", originalPrice = 1250, currency = "JPY"), Locale.US))
        assertNull(formatOfferPrice(offer.copy(originalPrice = 1999), Locale.US))
    }

    @Test fun storeFormattedPricesRetainTheCurrency() {
        assertEquals("19.99 USD", formatOfferPrice(offer.copy(store = "gog", priceText = "19.99", currency = "USD"), Locale.US))
        assertEquals("19,99 USD", formatOfferPrice(offer.copy(priceText = "19,99 USD", currency = "USD"), Locale.US))
    }
}
