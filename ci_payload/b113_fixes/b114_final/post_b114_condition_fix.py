#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: post_b114_condition_fix.py <android-source-root>")
root = Path(sys.argv[1])
p = root / "app/src/main/java/tr/borsatakip/v5/ui/BistScanActivity.kt"
s = p.read_text(encoding="utf-8")
old = "if (enabled && !productionBackendConfigured()) {"
new = "if (!productionBackendConfigured() && enabled) {"
count = s.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one waiting-state condition after final applicator, got {count}")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")
print("B114 waiting-state condition disambiguated without changing behavior")
