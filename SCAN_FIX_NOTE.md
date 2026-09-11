# Tarama düzeltmesi

Bu dal, temiz kurulumda Production backend yapılandırılmamışsa BIST taramasının hiç başlayamaması sorununu düzeltir.

- Production backend varsa öncelik değişmez.
- Temiz kurulumda deneysel Yahoo yedeği varsayılan olarak açıktır.
- Sembol önbelleği boşsa yalnız ticker evrenini kurmak için herkese açık BIST hisse listesi best-effort okunur.
- Fiyat/OHLCV Yahoo chart endpoint'inden alınır ve her zaman YEDEK / GECİKMELİ / deneysel olarak etiketlenir.
- TradingView veri sağlayıcısı olarak kullanılmaz.
- Sahte fiyat veya sahte sinyal üretilmez.
