# BORSA TAKİP V5.1.27

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

BIST evreni sabit 28 sembole bağlı değildir. Normal kullanımda sembol listesi sağlayıcıdan dinamik alınır ve cihazda önbelleğe kaydedilir. Küçük yerel liste yalnızca ana sağlayıcıya daha önce hiç erişilememişse yedek kaynağın güvenlik listesi olarak kullanılır.

## Teknik özellikler

- Koyu lacivert mobil arayüz
- BIST tarama ve gerçek sembol ilerlemesi
- EMA20/50/200, Wilder RSI14, MACD, Bollinger, ATR, VWAP
- Fırsat skoru, risk skoru, LONG/SHORT değerlendirmesi
- VİOP için ayrı sözleşme modeli
- Favoriler ve fırsat bildirim altyapısı
- HTTPS zorunlu ana veri servisi
- İsteğe bağlı Yahoo gecikmeli yedek kaynak

## Release imzası

Release anahtarı repoya eklenmez. GitHub Actions aşağıdaki repository secret'ları tanımlıysa kalıcı release keystore ile imzalı APK üretir:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Bu secret'lar yoksa workflow yalnızca debug APK üretir. Aynı `applicationId` (`tr.borsatakip.v5`) ve aynı release keystore gelecek tüm sürümlerde korunmalıdır; aksi hâlde Android mevcut uygulamanın üzerine güncelleme yapmaz.

## Derleme

`main` dalına her gönderim `Android Build` workflow'unu çalıştırır. Release secret'ları tanımlıysa artifact içinde `BorsaTakipV5.1.27-release.apk`, tanımlı değilse `BorsaTakipV5.1.27-debug.apk` bulunur.
