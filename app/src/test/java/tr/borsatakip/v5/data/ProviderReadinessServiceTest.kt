package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderReadinessServiceTest {
    @Test fun validHttps_acceptsProductionHttpsHost() {
        assertTrue(ProviderReadinessService.isValidHttps("https://backend.example.com"))
    }

    @Test fun validHttps_rejectsHttp() {
        assertFalse(ProviderReadinessService.isValidHttps("http://backend.example.com"))
    }

    @Test fun validHttps_rejectsMissingHostAndMalformedUrl() {
        assertFalse(ProviderReadinessService.isValidHttps("https://"))
        assertFalse(ProviderReadinessService.isValidHttps("not-a-url"))
    }

    @Test fun validHttps_rejectsEmbeddedCredentials() {
        assertFalse(ProviderReadinessService.isValidHttps("https://user:pass@backend.example.com"))
    }
}
