# BorsaTakip V5.1.29 — LRC Entegrasyonu

Bu sürüm, mevcut OHLCV/Canvas grafik motorunu değiştirmeden Linear Regression Channel desteğini grafik ve tarama/analiz motoruyla birlikte kullanır.

## Varsayılanlar
- LRC: açık
- Length: 100 Close mumu
- ±1σ: kapalı
- ±2σ: açık ve ana kanal
- ±3σ: kapalı
- Trend rengi: açık
- Pearson R: açık
- Kanal dolgusu: kapalı
- ±2σ kanal dışı uyarısı: açık

## Mimari
LRC matematiği `LinearRegressionChannelCalculator` içinde saf Kotlin olarak tutulur. Rolling sonuçlar OHLCV değiştiğinde önceden hesaplanır; `onDraw()` içinde regresyon/standart sapma/Pearson hesabı yapılmaz. `TechnicalOhlcvChartView` yalnız cache edilmiş sonucu çizer.

Tarama motorunda LRC sonucu `Opportunity.lrc` içine taşınır. Eğimin ham değeri yanında fiyat ölçeğine göre normalize edilmiş `%/bar` eğim de tutulur. EMA20/50/200, RSI14, MACD/Signal ve hacim mevcut davranışlarıyla korunur. LRC aynı doğrulanmış Close dizisini kullanır.

LRC tek başına AL/SAT sinyali üretmez. ±2σ kanal dışı durumları yalnız teknik bağlam bilgisidir. Gecikmeli/yedek veride eşik geçilse bile sonuç `RESEARCH_CANDIDATE` olarak sınıflanır; gerçek zamanlı `SIGNAL` sayılmaz.

## Güvenlik
Veri sayısı LRC length değerinden azsa kanal çizilmez. NaN/Infinity içeren pencere reddedilir. Sabit fiyat serisinde Pearson R güvenli biçimde 0 üretilir. Rolling ve kanal sınır durumları unit test kapsamındadır.
