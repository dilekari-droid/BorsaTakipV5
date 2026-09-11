# V5.1.27 Gerçek OHLCV Grafik Uygulaması

Bu dalda Hisse Detay ekranındaki eski basit fiyat çizgisi kaldırılmış, gerçek OHLCV verisine dayalı profesyonel teknik grafik eklenmiştir.

- Dönemler: 1G, 1H, 1A, 3A, 1Y.
- Her dönem seçimi veri kaynağına yeni range/interval isteği gönderir.
- Öncelik HTTPS production backend'dir; açıkça etkinse Yahoo yalnız YEDEK/GECİKMELİ fallback olarak kullanılır.
- Candlestick, EMA20/50/200, hacim, RSI14, MACD/Signal/Histogram aynı doğrulanmış OHLCV dizisinden hesaplanır.
- Geçersiz OHLC kayıtları çizilmez; timestamp tekrarları tekilleştirilir.
- Yatay kaydırma, pinch zoom ve mum seçimi desteklenir.
- Canlı olmayan veri CANLI etiketi almaz.
- Sahte mum, fiyat, hacim veya indikatör üretilmez.

Kabul notu: Derleme ve runtime doğrulaması başarısızsa özellik TAMAMLANDI olarak raporlanmamalıdır.

CI tetikleme notu: Bu revizyon PR doğrulama derlemesini ve kaynak paketlemesini yeniden tetiklemek için güncellenmiştir.
