#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply_b114_final.py <android-source-root>")

root = Path(sys.argv[1]).resolve()
payload = Path(__file__).resolve().parent
main = root / "app/src/main"
test = root / "app/src/test/java/tr/borsatakip/v5"


def read(rel: str) -> str:
    return (root / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# 1) Auto-scan preference must remain ON while the production data service is unavailable.
activity_path = "app/src/main/java/tr/borsatakip/v5/ui/BistScanActivity.kt"
activity = read(activity_path)
block_pattern = re.compile(
    r"\n\s*if \(enabled && !productionBackendConfigured\(\)\) \{.*?"
    r"return@setOnCheckedChangeListener\s*\n\s*\}\s*\n",
    re.DOTALL,
)
activity, n = block_pattern.subn("\n", activity, count=1)
if n != 1:
    raise RuntimeError(f"BistScanActivity backend-off switch block: expected 1, got {n}")
activity = replace_once(
    activity,
    "if (settings.autoScanEnabled && productionBackendConfigured()) {\n            AutoScanScheduler.reconcile(this, allowForegroundStart = true)\n        }",
    "if (settings.autoScanEnabled) {\n            AutoScanScheduler.reconcile(this, allowForegroundStart = true)\n        }",
    "onResume auto-scan reconcile",
)
activity = replace_once(
    activity,
    '!productionBackendConfigured() -> "Bekliyor • Production Backend gerekli"',
    '!productionBackendConfigured() -> "Açık • ${tf.label} • VERİ SERVİSİ BEKLENİYOR"',
    "auto-scan waiting label",
)
reconcile_marker = """            AutoScanScheduler.reconcile(this, allowForegroundStart = true)\n            refreshAutoScanUi()"""
reconcile_replacement = """            AutoScanScheduler.reconcile(this, allowForegroundStart = true)\n            if (enabled && !productionBackendConfigured()) {\n                status.text = \"VERİ SERVİSİ BEKLENİYOR\"\n                heroSubtitle.text = \"${selected.label} otomatik tarama AÇIK • Production Backend bekleniyor.\"\n            }\n            refreshAutoScanUi()"""
# Only change the switch-listener occurrence; there is one indented exactly this way in the reconstructed source.
activity = replace_once(activity, reconcile_marker, reconcile_replacement, "switch waiting status")
write(activity_path, activity)

# 2) The foreground service may start in WAITING state; it still must not fabricate data.
service_path = "app/src/main/java/tr/borsatakip/v5/worker/AutoScanForegroundService.kt"
service = read(service_path)
service = replace_once(
    service,
    'if (!settings.autoScanEnabled || !settings.baseUrl.startsWith("https://") || settings.apiKey.isBlank()) return false',
    'if (!settings.autoScanEnabled) return false',
    "foreground service start gate",
)
write(service_path, service)

# 3) Central trend policy based on the selected-timeframe technical snapshot.
trend_dst = main / "java/tr/borsatakip/v5/analysis/TrendUiPolicy.kt"
trend_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(payload / "TrendUiPolicy.kt", trend_dst)

# 4) Scan result card: keep signal badge semantics, but card/accent strength colour follows technical trend.
scan_path = "app/src/main/java/tr/borsatakip/v5/ui/ScanResultsAdapter.kt"
scan = read(scan_path)
scan = replace_once(
    scan,
    "import tr.borsatakip.v5.R\n",
    "import tr.borsatakip.v5.R\nimport tr.borsatakip.v5.analysis.TrendUiPolicy\nimport tr.borsatakip.v5.analysis.TrendUiStyle\n",
    "ScanResultsAdapter trend imports",
)
scan = replace_once(
    scan,
    "        val score = if (delayedObservation) item.score.coerceIn(0, 100) else item.finalSignalScore.coerceIn(0, 100)\n",
    "        val score = if (delayedObservation) item.score.coerceIn(0, 100) else item.finalSignalScore.coerceIn(0, 100)\n        val trendStyle = TrendUiPolicy.resolve(item)\n",
    "ScanResultsAdapter trend resolve",
)
scan = replace_once(
    scan,
    '            "TEKNİK PUAN • GECİKMELİ VERİ • AL/SAT YOK"',
    '            "TREND: ${trendStyle.arrow} ${trendStyle.label} • TEKNİK PUAN • GECİKMELİ VERİ • AL/SAT YOK"',
    "ScanResultsAdapter delayed trend label",
)
scan = replace_once(
    scan,
    '            "${strengthLabel(score)} • ${validityLabel(item.signalValidity)}"',
    '            "TREND: ${trendStyle.arrow} ${trendStyle.label} • ${strengthLabel(score)} • ${validityLabel(item.signalValidity)}"',
    "ScanResultsAdapter live trend label",
)
scan = replace_once(
    scan,
    "        applyVisuals(holder, direction, score, delayedObservation)\n",
    "        applyVisuals(holder, direction, score, delayedObservation, trendStyle)\n",
    "ScanResultsAdapter apply visuals call",
)
old_visual = """    private fun applyVisuals(holder: Holder, direction: String, score: Int, delayedObservation: Boolean) {
        val isLong = direction.equals("LONG", true)
        val isShort = direction.equals("SHORT", true)
        val accent = when {
            delayedObservation -> Color.rgb(96, 165, 250)
            isLong -> Color.rgb(0, 240, 128)
            isShort -> Color.rgb(255, 69, 69)
            else -> Color.rgb(250, 204, 21)
        }
        val dark = when {
            delayedObservation -> Color.rgb(10, 31, 52)
            isLong -> Color.rgb(0, 38, 31)
            isShort -> Color.rgb(48, 12, 18)
            else -> Color.rgb(48, 39, 8)
        }
        val strength = score / 100f
        val border = ColorUtils.blendARGB(accent, Color.WHITE, strength * 0.25f)
        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView, 14f)
            setColor(ColorUtils.blendARGB(Color.rgb(6, 38, 58), dark, 0.35f))
            setStroke(dp(holder.itemView, if (score >= 85) 2f else 1f).toInt().coerceAtLeast(1), border)
        }
        holder.direction.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView, 12f)
            setColor(ColorUtils.setAlphaComponent(accent, 42))
            setStroke(dp(holder.itemView, 1.5f).toInt().coerceAtLeast(1), accent)
        }
        holder.direction.setTextColor(accent)
        holder.strengthValue.setTextColor(accent)
        holder.strengthBar.progressTintList = ColorStateList.valueOf(accent)
        holder.strengthBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 40))
    }
"""
new_visual = """    private fun applyVisuals(
        holder: Holder,
        direction: String,
        score: Int,
        delayedObservation: Boolean,
        trendStyle: TrendUiStyle
    ) {
        val isLong = direction.equals("LONG", true)
        val isShort = direction.equals("SHORT", true)
        val signalAccent = when {
            delayedObservation -> Color.rgb(96, 165, 250)
            isLong -> Color.rgb(0, 240, 128)
            isShort -> Color.rgb(255, 69, 69)
            else -> Color.rgb(250, 204, 21)
        }
        val trendAccent = Color.rgb(trendStyle.red, trendStyle.green, trendStyle.blue)
        val strength = score / 100f
        val border = ColorUtils.blendARGB(trendAccent, Color.WHITE, strength * 0.20f)
        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView, 14f)
            setColor(ColorUtils.blendARGB(Color.rgb(6, 38, 58), trendAccent, 0.13f))
            setStroke(dp(holder.itemView, if (score >= 85) 2f else 1f).toInt().coerceAtLeast(1), border)
        }
        holder.direction.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView, 12f)
            setColor(ColorUtils.setAlphaComponent(signalAccent, 42))
            setStroke(dp(holder.itemView, 1.5f).toInt().coerceAtLeast(1), signalAccent)
        }
        holder.direction.setTextColor(signalAccent)
        holder.strengthValue.setTextColor(trendAccent)
        holder.strengthBar.progressTintList = ColorStateList.valueOf(trendAccent)
        holder.strengthBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(trendAccent, 40))
    }
"""
scan = replace_once(scan, old_visual, new_visual, "ScanResultsAdapter trend visuals")
write(scan_path, scan)

# 5) Opportunity card: preserve signal-specific score styling, use trend for card/badge border/background.
opp_path = "app/src/main/java/tr/borsatakip/v5/ui/OpportunityAdapter.kt"
opp = read(opp_path)
opp = replace_once(
    opp,
    "import tr.borsatakip.v5.analysis.OpportunityDiscoveryPresentation\n",
    "import tr.borsatakip.v5.analysis.OpportunityDiscoveryPresentation\nimport tr.borsatakip.v5.analysis.TrendUiPolicy\n",
    "OpportunityAdapter trend import",
)
opp = replace_once(
    opp,
    "        val p = OpportunityDiscoveryPresentation.from(x)\n",
    "        val p = OpportunityDiscoveryPresentation.from(x)\n        val trendStyle = TrendUiPolicy.resolve(x)\n",
    "OpportunityAdapter trend resolve",
)
opp = replace_once(
    opp,
    "        val signalAccent = signalStyle?.let { Color.rgb(it.red, it.green, it.blue) } ?: Color.rgb(180, 190, 200)\n",
    "        val signalAccent = signalStyle?.let { Color.rgb(it.red, it.green, it.blue) } ?: Color.rgb(180, 190, 200)\n        val trendAccent = Color.rgb(trendStyle.red, trendStyle.green, trendStyle.blue)\n",
    "OpportunityAdapter trend accent",
)
opp = replace_once(
    opp,
    '            if (x.scanCadenceMinutes > 0) append(" • Cadence ${x.scanCadenceMinutes} DK")\n',
    '            if (x.scanCadenceMinutes > 0) append(" • Cadence ${x.scanCadenceMinutes} DK")\n            append(" • Trend ${trendStyle.arrow} ${trendStyle.label}")\n',
    "OpportunityAdapter trend label",
)
opp = replace_once(
    opp,
    "            setColor(ColorUtils.blendARGB(Color.rgb(5, 28, 45), signalAccent, 0.055f))\n            setStroke((1.2f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(signalAccent, 170))",
    "            setColor(ColorUtils.blendARGB(Color.rgb(5, 28, 45), trendAccent, 0.10f))\n            setStroke((1.2f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(trendAccent, 190))",
    "OpportunityAdapter card trend colour",
)
badge_marker = "        holder.scoreBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(signalAccent, 34))\n"
badge_new = badge_marker + """        holder.badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * holder.itemView.resources.displayMetrics.density
            setColor(ColorUtils.setAlphaComponent(trendAccent, 52))
            setStroke((1.2f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), trendAccent)
        }
        holder.badge.contentDescription = "${x.symbol}, trend ${trendStyle.label}"
"""
opp = replace_once(opp, badge_marker, badge_new, "OpportunityAdapter badge trend colour")
write(opp_path, opp)

# 6) Add regression/acceptance tests.
trend_test_dst = test / "analysis/TrendUiPolicyTest.kt"
trend_test_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(payload / "TrendUiPolicyTest.kt", trend_test_dst)
source_test_dst = test / "data/B114AutoScanTimeframeTrendSourceTest.kt"
source_test_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(payload / "B114AutoScanTimeframeTrendSourceTest.kt", source_test_dst)

print("B114 combined auto-scan + timeframe + trend-colour fix applied successfully")
