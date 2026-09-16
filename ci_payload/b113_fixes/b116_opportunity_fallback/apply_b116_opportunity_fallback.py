#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/src")
p = root / "app/src/main/java/tr/borsatakip/v5/ui/OpportunityActivity.kt"
s = p.read_text()

# Imports needed by the selected-timeframe delayed-safe opportunity scan path.
s = s.replace(
    "import tr.borsatakip.v5.data.LastSuccessfulScanStore\nimport tr.borsatakip.v5.data.ProviderRouter",
    "import tr.borsatakip.v5.data.LastSuccessfulScanStore\nimport tr.borsatakip.v5.data.IntervalMarketDataProvider\nimport tr.borsatakip.v5.data.ProviderRouter"
)
s = s.replace(
    "import tr.borsatakip.v5.scan.BistScanner\nimport tr.borsatakip.v5.scan.ScanStatus",
    "import tr.borsatakip.v5.scan.BistScanMode\nimport tr.borsatakip.v5.scan.BistScanner\nimport tr.borsatakip.v5.scan.ScanStatus"
)

old_summary = '''                    val settings = SettingsStore(this@OpportunityActivity)\n                    summary.text = when {\n                        settings.baseUrl.startsWith("https://") -> "Fırsat verileri toplanıyor • gerçek provider verisi kullanılıyor"\n                        settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> "Fırsat verileri toplanıyor • deneysel/gecikmeli yedek aktif"\n                        else -> "Üretim backend yapılandırılmamış • veri yoksa fırsat üretilmez"\n                    }'''
new_summary = '''                    val settings = SettingsStore(this@OpportunityActivity)\n                    val productionConfigured = productionBackendConfigured(settings)\n                    val timeframeLabel = ScanTimeframe.displayLabel(settings.analysisTimeframeMinutes)\n                    summary.text = if (productionConfigured) {\n                        "Fırsat verileri toplanıyor • Production provider öncelikli • $timeframeLabel"\n                    } else {\n                        "Fırsat verileri toplanıyor • Yahoo Finance • YEDEK/GECİKMELİ • $timeframeLabel"\n                    }'''
if old_summary not in s:
    raise SystemExit("B116: opportunity scan summary block not found")
s = s.replace(old_summary, new_summary, 1)

# A realtime snapshot is only valid when both backend URL and API key are configured.
s = s.replace(
    '                    if (settings.baseUrl.startsWith("https://")) {\n                        val realtimeSnapshot = RealtimeScannerClient(this@OpportunityActivity)',
    '                    if (productionConfigured) {\n                        val realtimeSnapshot = RealtimeScannerClient(this@OpportunityActivity)',
    1
)

old_scanner = '''                    val scanner = BistScanner(ProviderRouter(this@OpportunityActivity, experimentalFallbackOverride = false), SignalHistoryRecorder(this@OpportunityActivity))\n                    val finalState = scanner.scan { state ->'''
new_scanner = '''                    // Production backend remains the preferred source. When it is absent or a\n                    // primary request fails, allow the real Yahoo delayed fallback instead of\n                    // aborting before BIST symbols can be loaded. The fallback is never promoted\n                    // to realtime/verified output.\n                    val baseProvider = ProviderRouter(\n                        this@OpportunityActivity,\n                        experimentalFallbackOverride = true\n                    )\n                    val scanProvider = IntervalMarketDataProvider(\n                        baseProvider,\n                        settings.analysisTimeframeMinutes\n                    )\n                    val scanner = BistScanner(\n                        scanProvider,\n                        SignalHistoryRecorder(this@OpportunityActivity),\n                        BistScanMode.DELAYED_ANALYSIS\n                    )\n                    val finalState = scanner.scan { state ->'''
if old_scanner not in s:
    raise SystemExit("B116: old OpportunityActivity scanner block not found")
s = s.replace(old_scanner, new_scanner, 1)

old_history = '''                    val run = finalState.scanRun\n                    val historySuffix = finalState.historyError?.let { " • Geçmiş hatası: $it" }\n                        ?: " • ${finalState.historyPersisted} yeni sinyal geçmişe yazıldı"\n                    when {\n                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE -> {\n                            val results = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)\n                            AppSession.lastOpportunities = results\n                            lastSuccessfulRun = run\n                            historyStore.save(run, results)\n                            applyDiscoveryFilter("SON GÜNCELLEME • ${formatRunTime(run)} • ${results.size} aday$historySuffix")\n                        }\n                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.PARTIAL && finalState.successful > 0 -> {\n                            val partial = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)\n                            AppSession.lastOpportunities = partial\n                            bindFiltered(partial, "KISMİ VERİ • Başarılı ${finalState.successful}/${finalState.total} • Hatalı/atlanan ${finalState.skipped}$historySuffix")\n                        }\n                        finalState.status == ScanStatus.COMPLETED -> bindFiltered(emptyList(), "Kalite eşiğini geçen fırsat bulunamadı.")\n                    }'''
new_history = '''                    val run = finalState.scanRun\n                    // This scanner path is deliberately DELAYED_ANALYSIS: it may use Yahoo fallback\n                    // and therefore must never overwrite the verified production history/store.\n                    val delayedSuffix = " • doğrulanmış sinyal geçmişine yazılmadı"\n                    when {\n                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE -> {\n                            val results = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)\n                            AppSession.lastOpportunities = results\n                            bindFiltered(\n                                results,\n                                "YEDEK/GECİKMELİ FIRSAT GÖZLEMİ • ${formatRunTime(run)} • ${results.size} aday$delayedSuffix"\n                            )\n                        }\n                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.PARTIAL && finalState.successful > 0 -> {\n                            val partial = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)\n                            AppSession.lastOpportunities = partial\n                            bindFiltered(\n                                partial,\n                                "KISMİ YEDEK/GECİKMELİ VERİ • Başarılı ${finalState.successful}/${finalState.total} • Hatalı/atlanan ${finalState.skipped}$delayedSuffix"\n                            )\n                        }\n                        finalState.status == ScanStatus.COMPLETED -> bindFiltered(\n                            emptyList(),\n                            "YEDEK/GECİKMELİ ANALİZ • Kalite eşiğini geçen fırsat bulunamadı.$delayedSuffix"\n                        )\n                    }'''
if old_history not in s:
    raise SystemExit("B116: old opportunity persistence block not found")
s = s.replace(old_history, new_history, 1)

# Do not open the production websocket when production credentials are absent.
old_onstart = '''        val settings = SettingsStore(this)\n        realtimeSocket.connect(\n            minScore = 55,'''
new_onstart = '''        val settings = SettingsStore(this)\n        if (!productionBackendConfigured(settings)) {\n            if (AppSession.lastOpportunities.isEmpty()) {\n                summary.text = "Yahoo Finance • YEDEK/GECİKMELİ hazır • FIRSATLARI YENİLE ile analiz başlat"\n            }\n            return\n        }\n        realtimeSocket.connect(\n            minScore = 55,'''
if old_onstart not in s:
    raise SystemExit("B116: OpportunityActivity onStart socket block not found")
s = s.replace(old_onstart, new_onstart, 1)

# Make empty-state wording match the actual safe fallback contract.
old_existing = '''            val text = when {\n                settings.baseUrl.startsWith("https://") -> "Kayıtlı fırsat yok • FIRSATLARI YENİLE ile gerçek veriyi değerlendir"\n                settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> "Kayıtlı fırsat yok • deneysel/gecikmeli yedek açık"\n                else -> "Üretim backend yapılandırılmamış • fırsat üretilmez"\n            }'''
new_existing = '''            val text = if (productionBackendConfigured(settings)) {\n                "Kayıtlı fırsat yok • FIRSATLARI YENİLE ile Production verisini değerlendir"\n            } else {\n                "Kayıtlı doğrulanmış fırsat yok • Yahoo YEDEK/GECİKMELİ analiz kullanılabilir"\n            }'''
if old_existing not in s:
    raise SystemExit("B116: OpportunityActivity existing-state block not found")
s = s.replace(old_existing, new_existing, 1)

# Benchmark absence must remain explicit and neutral; no invented market regime.
s = s.replace(
    '            marketRegime.text = fromItems?.let { "${it.marketRegime} • %${it.marketRegimeConfidence}" } ?: "Benchmark verisi yok"',
    '            marketRegime.text = fromItems?.let { "${it.marketRegime} • %${it.marketRegimeConfidence}" } ?: "Benchmark verisi alınamadı"',
    1
)

# The current scan universe should drive the watched metric before an older verified run.
s = s.replace(
    '        val watched = lastSuccessfulRun?.count?.takeIf { it > 0 } ?: items.size',
    '        val watched = AppSession.lastScanState?.total?.takeIf { it > 0 } ?: lastSuccessfulRun?.count?.takeIf { it > 0 } ?: items.size',
    1
)

helper_anchor = '''    private fun formatRunTime(run: ScanRun): String {\n        val ts = run.scanCompletedAt ?: run.scanStartedAt\n        return if (ts > 0) dateFormat.format(Date(ts)) else "zaman bilinmiyor"\n    }\n'''
helper = helper_anchor + '''\n    private fun productionBackendConfigured(settings: SettingsStore): Boolean =\n        settings.baseUrl.startsWith("https://") && settings.apiKey.isNotBlank()\n'''
if helper_anchor not in s:
    raise SystemExit("B116: helper insertion anchor not found")
s = s.replace(helper_anchor, helper, 1)

# Acceptance checks: the old hard-disable must be gone and delayed-safe path must exist.
required = [
    "experimentalFallbackOverride = true",
    "IntervalMarketDataProvider(",
    "BistScanMode.DELAYED_ANALYSIS",
    "Yahoo Finance • YEDEK/GECİKMELİ",
    "doğrulanmış sinyal geçmişine yazılmadı",
    "Benchmark verisi alınamadı",
    "AppSession.lastScanState?.total?.takeIf { it > 0 }",
]
for token in required:
    if token not in s:
        raise SystemExit(f"B116: required token missing after patch: {token}")
if "experimentalFallbackOverride = false" in s:
    raise SystemExit("B116: stale Opportunity fallback hard-disable still present")

p.write_text(s)
print("B116 opportunity symbols/fallback fix applied successfully")
