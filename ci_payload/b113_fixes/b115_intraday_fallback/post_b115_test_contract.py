#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match in {path}, got {count}")
    path.write_text(text.replace(old, new, 1))

b114 = root / "app/src/test/java/tr/borsatakip/v5/data/B114AutoScanTimeframeTrendSourceTest.kt"
replace_once(
    b114,
    '        assertTrue(src.contains("VERİ SERVİSİ BEKLENİYOR"))\n',
    '        assertTrue(src.contains("Yahoo YEDEK/GECİKMELİ"))\n',
    "B114 fallback status contract",
)

auto = root / "app/src/test/java/tr/borsatakip/v5/worker/AutomaticScanSourceContractTest.kt"
replace_once(
    auto,
    '''    fun backendMissingIsNotMisreportedAsUnsupportedTimeframe() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(src.contains("VERİ SERVİSİ YAPILANDIRILMAMIŞ"))
        assertFalse(src.contains("status.text = \\"Periyot desteklenmiyor\\""))
        assertTrue(src.contains("BACKEND_NOT_CONFIGURED"))
    }
''',
    '''    fun backendMissingUsesExplicitDelayedFallbackNotUnsupportedTimeframe() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertFalse(src.contains("status.text = \\"Periyot desteklenmiyor\\""))
        assertFalse(src.contains("BACKEND_NOT_CONFIGURED"))
        assertFalse(src.contains("VERİ SERVİSİ KULLANILAMIYOR • Production Backend bağlantısı gerekli"))
        assertTrue(src.contains("Yahoo Finance • YEDEK/GECİKMELİ"))
        assertTrue(src.contains("BistScanMode.DELAYED_ANALYSIS"))
    }
''',
    "automatic backend fallback contract",
)
replace_once(
    auto,
    '''    fun automaticRunnerUsesSameRealProviderScannerPipeline() {
        val src = source("java/tr/borsatakip/v5/worker/AutomaticScanRunner.kt")
        assertTrue(src.contains("BackendPreflightClient(app).checkBist()"))
        assertTrue(src.contains("ProviderRouter(app, experimentalFallbackOverride = false)"))
        assertTrue(src.contains("IntervalMarketDataProvider(baseProvider, timeframe.storedMinutes)"))
        assertTrue(src.contains("BistScanner(provider, SignalHistoryRecorder(app), BistScanMode.REALTIME_ONLY)"))
        assertFalse(src.contains("mock", ignoreCase = true))
    }
''',
    '''    fun automaticRunnerUsesProductionOrExplicitDelayedProviderPipeline() {
        val src = source("java/tr/borsatakip/v5/worker/AutomaticScanRunner.kt")
        assertTrue(src.contains("if (productionReady) BackendPreflightClient(app).checkBist() else null"))
        assertTrue(src.contains("ProviderRouter(app, experimentalFallbackOverride = !productionReady)"))
        assertTrue(src.contains("IntervalMarketDataProvider(baseProvider, timeframe.storedMinutes)"))
        assertTrue(src.contains("if (productionReady) BistScanMode.REALTIME_ONLY else BistScanMode.DELAYED_ANALYSIS"))
        assertTrue(src.contains("BistScanner(provider, SignalHistoryRecorder(app), scanMode)"))
        assertTrue(src.contains("if (productionReady) LastSuccessfulScanStore(app).save(run, final.results)"))
        assertFalse(src.contains("mock", ignoreCase = true))
    }
''',
    "automatic provider pipeline contract",
)

print("B115 legacy source-contract tests migrated successfully")
