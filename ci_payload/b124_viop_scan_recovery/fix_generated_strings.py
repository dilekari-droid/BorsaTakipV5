from pathlib import Path

path = Path('app/src/main/java/tr/borsatakip/v5/ui/MarketInstrumentDetailActivity.kt')
text = path.read_text(encoding='utf-8')
fixes = {
    'append("BU DEĞER VİOP KONTRAT FİYATI DEĞİLDİR.\n")': r'append("BU DEĞER VİOP KONTRAT FİYATI DEĞİLDİR.\n")',
    'append("XU030 spot/dayanak referansıdır; VİOP kontrat fiyatı, hacim ve açık pozisyon üretilmez.\n")': r'append("XU030 spot/dayanak referansıdır; VİOP kontrat fiyatı, hacim ve açık pozisyon üretilmez.\n")',
}
for old, new in fixes.items():
    if old not in text:
        raise SystemExit(f'MISSING_GENERATED_LITERAL: {old!r}')
    text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')
print('B124_STRING_ESCAPES_FIXED')
