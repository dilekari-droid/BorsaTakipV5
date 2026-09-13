# BORSA TAKİP V5.1.28

Android/Kotlin tabanlı BIST ve VİOP takip uygulaması.

## Veri mimarisi

Uygulama tek bir veri sağlayıcısına bağlı değildir.

- `MarketDataProvider`: ortak sağlayıcı sözleşmesi
- `MobileMarketDataProvider`: ana BIST veri sağlayıcısı; lisanslı/gerçek zamanlı backend bu katmana bağlanır
- `BackendProvider`: VİOP backend bağlantısı
- `ProviderRouter`: ana sağlayıcıyı kullanır, başarısızlıkta izin verilmişse gecikmeli yedeğe geçer
- `YahooFallbackProvider`: yalnızca yedek/gecikmeli kaynaktır; canlı veri olarak etiketlenmez
- `LiveMarketSocket`: HTTPS backend adresinden türetilen `wss://.../v1/live` sözleşmesine bağlanır

Ana mobil backend şu sözleşmeleri sağlamalıdır:

- `GET /v1/health`
- `GET /v1/bist/symbols`
- `GET /v1/bist/history/{symbol}?range=1y&interval=1d`
- `GET /v1/viop/contracts`
- `WSS /v1/live`

BIST evreni sabit birkaç sembole bağlı değildir. Normal kullanımda sembol listesi sağlayıcıdan dinamik alınır ve cihazda önbelleğe kaydedilir. Üretim backend yapılandırılmamışsa, kullanıcıya açıkça gecikmeli/deneysel olarak etiketlenen yedek veri yolu kullanılabilir; gecikmeli veri canlı veri gibi gösterilmez.

## Teknik özellikler

- Koyu lacivert mobil arayüz
- Dashboard odaklı ana ekran
- BIST tarama ve gerçek sembol ilerlemesi
- EMA20/50/200, Wilder RSI14, MACD, Bollinger, ATR, VWAP
- Fırsat skoru, risk skoru, LONG/SHORT değerlendirmesi
- Çelişkili veya yetersiz sinyaller için NÖTR sonucu
- Ölçülen veri kalitesine dayalı veri güveni ve riskle düzeltilmiş nihai sinyal
- Aşağı yönlü oynaklık ve azami düşüşü içeren açıklanabilir risk bileşenleri
- VİOP için ayrı sözleşme modeli ve kart görünümü
- Favoriler ve fırsat bildirim altyapısı
- HTTPS zorunlu ana veri servisi
- İsteğe bağlı Yahoo gecikmeli yedek kaynak
- Gecikmeli/doğrulanmamış veriyi gerçek zamanlı diye göstermeyen veri bütünlüğü yaklaşımı

## Sürüm yönetimi

Uygulama içindeki sürüm etiketi `BuildConfig.VERSION_NAME` üzerinden üretilir. Gradle, APK, ana ekran ve README aynı sürüm kimliğini kullanmalıdır.

## Release imzası

Release anahtarı repoya eklenmez. GitHub Actions aşağıdaki repository secret'ları tanımlıysa kalıcı release keystore ile imzalı APK üretir:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Bu secret'lar yoksa workflow yalnızca debug APK üretir. Aynı `applicationId` (`tr.borsatakip.v5`) ve aynı release keystore gelecek tüm sürümlerde korunmalıdır; aksi hâlde Android mevcut uygulamanın üzerine güncelleme yapmaz.

## Derleme

`main` dalına her gönderim `Android Build` workflow'unu çalıştırır. Release secret'ları tanımlıysa artifact içinde `BorsaTakipV5.1.28-release.apk`, tanımlı değilse `BorsaTakipV5.1.28-debug.apk` bulunur.
