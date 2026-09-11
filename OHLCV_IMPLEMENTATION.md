# V5.1.29 Gerçek OHLCV Grafik ve Analiz Uygulaması

Hisse Detay ekranında gerçek OHLCV verisine dayalı teknik grafik kullanılmaktadır.

- Dönemler: 3 Dk, 5 Dk, 15 Dk, 1 Saat, 1 Gün, Tüm Zamanlar.
- Her dönem seçimi veri kaynağına uygun range/interval isteği gönderir.
- Öncelik HTTPS production backend'dir; açıkça etkinse Yahoo yalnız YEDEK/GECİKMELİ fallback olarak kullanılır.
- Candlestick, EMA20/50/200, hacim, RSI14, MACD/Signal/Histogram ve LRC aynı doğrulanmış OHLCV dizisinden hesaplanır.
- LRC100 varsayılan ±2σ ana kanalı, Pearson R ve normalize eğim bilgisi desteklenir.
- Geçersiz OHLC kayıtları çizilmez; timestamp tekrarları tekilleştirilir.
- Yatay kaydırma, pinch zoom ve mum seçimi desteklenir.
- Canlı olmayan veri CANLI etiketi almaz.
- Gecikmeli/yedek veri gerçek zamanlı AL/SAT sinyali üretmez; yalnız teknik araştırma adayı olabilir.
- Sahte mum, fiyat, hacim veya indikatör üretilmez.

Kabul notu: Derleme ve runtime doğrulaması başarısızsa özellik TAMAMLANDI olarak raporlanmamalıdır.
