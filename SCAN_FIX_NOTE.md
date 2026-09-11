# V5.1.29 Tarama ve veri modu düzeltmesi

Bu sürüm, Production backend yapılandırılmamışsa BIST taramasının gecikmeli/yedek veriyle teknik araştırma yapabilmesini korurken gerçek zamanlı sinyal ile araştırma sonucunu kesin olarak ayırır.

- Production backend varsa öncelik değişmez.
- Temiz kurulumda deneysel Yahoo yedeği kullanılabilir.
- Sembol önbelleği boşsa ticker evreni best-effort kurulabilir.
- Fiyat/OHLCV Yahoo chart endpoint'inden alınır ve her zaman YEDEK / GECİKMELİ / deneysel olarak etiketlenir.
- Gecikmeli veri teknik analiz ve `RESEARCH_CANDIDATE` üretebilir; `SIGNAL` üretemez.
- `SIGNAL` yalnız gerçek zamanlı bütünlük koşullarını geçen veriye ayrılmıştır.
- TradingView veri sağlayıcısı olarak kullanılmaz.
- Sahte fiyat, hacim, OHLCV veya sahte sinyal üretilmez.
