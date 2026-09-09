# BORSA TAKİP V5.0.0

Android/Kotlin tabanlı BIST ve VİOP takip uygulaması.

## Temel özellikler

- Koyu lacivert arayüz ve kart tabanlı tasarım
- BIST tarama ve gerçek tarama ilerlemesi
- Teknik analiz: EMA20/50/200, Wilder RSI14, MACD, Bollinger, ATR, VWAP
- Fırsat skoru ve ayrı risk değerlendirmesi
- LONG/SHORT değerlendirmesi
- VİOP için ayrı sözleşme veri modeli
- Favoriler, ayarlar ve bildirim altyapısı
- Yahoo Finance üzerinden BIST verisi; veri gecikmeli olabilir
- VİOP/KAP gibi alanlar için doğrulanmış harici sağlayıcı/backend bağlantısı gerekir

## Derleme

GitHub Actions, `main` dalına gönderimden sonra debug APK üretir. Ayrıca Actions ekranından `Android Build` iş akışı elle de çalıştırılabilir.

APK artifact adı: `BorsaTakipV5-debug`.
