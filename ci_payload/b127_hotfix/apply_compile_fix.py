from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected source block not found in {path}: {old!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# ViopUnderlyingScanner consumes a MarketDataProvider, not an Activity Context.
activity = "app/src/main/java/tr/borsatakip/v5/ui/ViopActivity.kt"
p = Path(activity)
text = p.read_text(encoding="utf-8")
if "import tr.borsatakip.v5.data.ProviderRouter\n" not in text:
    anchor = "import tr.borsatakip.v5.data.ProviderReadinessService\n"
    if anchor not in text:
        raise SystemExit("ProviderReadinessService import anchor missing")
    text = text.replace(anchor, anchor + "import tr.borsatakip.v5.data.ProviderRouter\n", 1)
p.write_text(text, encoding="utf-8")
replace_exact(activity, "underlyingScanner = ViopUnderlyingScanner(this)", "underlyingScanner = ViopUnderlyingScanner(ProviderRouter(this))")
replace_exact(activity, "scanner.scan { p -> runOnUiThread { status.text = progressText(p) } }", "scanner.runViop { p -> runOnUiThread { status.text = progressText(p) } }")

# The background worker also uses the canonical ScanOrchestrator.
worker = "app/src/main/java/tr/borsatakip/v5/worker/OpportunityWorker.kt"
p = Path(worker)
text = p.read_text(encoding="utf-8")
if "import tr.borsatakip.v5.scan.ScanOrchestrator\n" not in text:
    anchor = "import tr.borsatakip.v5.model.SignalValidity\n"
    if anchor not in text:
        raise SystemExit("SignalValidity import anchor missing")
    text = text.replace(anchor, anchor + "import tr.borsatakip.v5.scan.ScanOrchestrator\n", 1)
p.write_text(text, encoding="utf-8")

# Kotlin does not allow continue from the inline Result.getOrElse lambda here
# without an experimental language feature. Keep the fail-closed loop explicit.
resolver = "app/src/main/java/tr/borsatakip/v5/data/ViopContractOpenResolver.kt"
replace_exact(
    resolver,
    '''            val quote = loadQuote(contract.symbol).getOrElse {\n                failures += "${contract.symbol}:QUOTE_ERROR:${it.message.orEmpty()}"\n                continue\n            }''',
    '''            val quoteResult = loadQuote(contract.symbol)\n            if (quoteResult.isFailure) {\n                failures += "${contract.symbol}:QUOTE_ERROR:${quoteResult.exceptionOrNull()?.message.orEmpty()}"\n                continue\n            }\n            val quote = quoteResult.getOrThrow()'''
)
replace_exact(
    resolver,
    '''            val history = loadHistory(contract.symbol).getOrElse {\n                failures += "${contract.symbol}:HISTORY_ERROR:${it.message.orEmpty()}"\n                continue\n            }''',
    '''            val historyResult = loadHistory(contract.symbol)\n            if (historyResult.isFailure) {\n                failures += "${contract.symbol}:HISTORY_ERROR:${historyResult.exceptionOrNull()?.message.orEmpty()}"\n                continue\n            }\n            val history = historyResult.getOrThrow()'''
)

# MtfHistoryCache gained an optional Stats parameter after loader. Calls that use
# a trailing lambda would bind that lambda to Stats instead of loader. Use named
# loader arguments so the regression tests compile against the stricter API.
mtf_test = "app/src/test/java/tr/borsatakip/v5/analysis/B119StabilityPerformanceTest.kt"
replace_exact(
    mtf_test,
    'MtfHistoryCache.loadFresh("P", "XU030", "60m", 5 * 60_000L, { now }) { loads++; stale }',
    'MtfHistoryCache.loadFresh("P", "XU030", "60m", 5 * 60_000L, { now }, loader = { loads++; stale })'
)
replace_exact(
    mtf_test,
    'MtfHistoryCache.loadFresh("P", "XU030", "60m", 5 * 60_000L, { now }) { loads++; fresh }',
    'MtfHistoryCache.loadFresh("P", "XU030", "60m", 5 * 60_000L, { now }, loader = { loads++; fresh })'
)

# System.getProperty is a Java platform type and may be null. The project treats
# compiler warnings as errors, so source-only tests must provide a deterministic
# fallback rather than passing a nullable platform value to File(String).
for source_test in (
    "app/src/test/java/tr/borsatakip/v5/production/ProductionBackendConfigSourceTest.kt",
    "app/src/test/java/tr/borsatakip/v5/ui/ViopProviderFallbackPolicySourceTest.kt",
):
    p = Path(source_test)
    text = p.read_text(encoding="utf-8")
    old = 'File(System.getProperty("user.dir"))'
    count = text.count(old)
    if count != 2:
        raise SystemExit(f"Expected two user.dir File calls in {source_test}, found {count}")
    p.write_text(text.replace(old, 'File(System.getProperty("user.dir") ?: ".")'), encoding="utf-8")

print("B127 compile corrections applied")
