# BorsaTakip V5.1.27 — LRC Entegrasyonu

Bu dal, mevcut OHLCV/Canvas grafik motorunu değiştirmeden Linear Regression Channel desteği ekler.

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

EMA20/50/200, RSI14, MACD/Signal ve hacim mevcut davranışlarıyla korunur. LRC aynı doğrulanmış Close dizisini kullanır. ±2σ kanal dışı durumunda RSI ve MACD yalnız doğrulama bilgisi üretir; LRC tek başına AL/SAT sinyali oluşturmaz.

## Güvenlik
Veri sayısı LRC length değerinden azsa kanal çizilmez. NaN/Infinity içeren pencere reddedilir. Sabit fiyat serisinde Pearson R güvenli biçimde 0 üretilir.
