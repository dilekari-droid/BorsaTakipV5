package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.borsatakip.v5.data.favorites.FavoriteRepository

class FavoriteNormalizationTest {
    @Test
    fun bistSymbolVariantsNormalizeToSameKey() {
        assertEquals("ASELS", FavoriteRepository.normalizeSymbol("ASELS"))
        assertEquals("ASELS", FavoriteRepository.normalizeSymbol("asels"))
        assertEquals("ASELS", FavoriteRepository.normalizeSymbol("ASELS.IS"))
        assertEquals("ASELS", FavoriteRepository.normalizeSymbol("BIST:ASELS"))
    }

    @Test
    fun marketNamesNormalizeSeparately() {
        assertEquals("BIST", FavoriteRepository.normalizeMarket("bist"))
        assertEquals("VIOP", FavoriteRepository.normalizeMarket("viop"))
        assertEquals("VIOP", FavoriteRepository.normalizeMarket("VİOP"))
    }
}
