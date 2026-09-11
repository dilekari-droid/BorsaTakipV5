# BORSA TAKİP V5.1.29

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

BIST evreni sabit birkaç sembole bağlı değildir. Normal kullanımda sembol listesi sağlayıcıdan dinamik alınır ve cihazda önbelleğe kaydedilir.

## V5.1.29 veri/sinyal ayrımı

Gerçek zamanlı bütünlük koşullarını geçen veri ile gecikmeli/araştırma verisi aynı anlamda değerlendirilmez:

- `SIGNAL`: yalnız gerçek zamanlı, güncel seansı içeren ve bütünlük politikasını geçen veride oluşabilir.
- `RESEARCH_CANDIDATE`: gecikmeli/yedek veride teknik eşik geçilmişse oluşur; **gerçek zamanlı AL/SAT sinyali değildir**.
- `NO_SIGNAL`: teknik uyum eşiği altında kalan analiz sonucudur.

Gecikmeli Yahoo verisi teknik analiz, LRC, EMA/RSI/MACD ve araştırma amaçlı aday üretiminde kullanılabilir; CANLI veya gerçek zamanlı AL/SAT sinyali olarak etiketlenmez.

## Teknik özellikler

- Koyu lacivert mobil arayüz
- Dashboard odaklı ana ekran
- BIST tarama ve sembol bazlı terminal durumları
- Veri toplama ve teknik analiz ilerlemesinin ayrı izlenmesi
- EMA20/50/200, Wilder RSI14, MACD, Bollinger, ATR, VWAP/VWMA
- LRC100, Pearson R, ±1σ / ±2σ / ±3σ ve normalize eğim bilgisi
- Teknik uyum skoru, risk skoru ve veri güveni skoru
- LONG/SHORT teknik yön değerlendirmesi
- VİOP için ayrı sözleşme modeli ve deneysel geliştirme modu
- Favoriler ve fırsat bildirim altyapısı
- HTTPS zorunlu ana veri servisi
- İsteğe bağlı Yahoo gecikmeli yedek kaynak

## Skorların anlamı

`finalSignalScore` bir olasılık veya başarı yüzdesi değildir. UI'da **Teknik Uyum Skoru** olarak sunulur. Risk skoru bundan ayrı tutulur. Veri güveni skoru da V5.1.29 itibarıyla veri modu, güncel seans bilgisi, bildirilen gecikme, veri yaşı ve analiz için mevcut mum sayısından türetilir; varsayılan sabit `100/Yüksek` değildir.

## Sürüm yönetimi

Her yeni gerçek üretimde `versionName` ve `versionCode` artırılır. Gradle, APK adı, kaynak ZIP adı, ana ekran ve dokümantasyon aynı sürüm kimliğini kullanmalıdır.

Mevcut sürüm:

- `versionName = 5.1.29`
- `versionCode = 79`

## Release imzası

Release anahtarı repoya eklenmez. GitHub Actions aşağıdaki repository secret'ları tanımlıysa kalıcı release keystore ile imzalı APK üretir:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Bu secret'lar yoksa workflow yalnızca debug APK üretir. Aynı `applicationId` (`tr.borsatakip.v5`) ve aynı release keystore gelecek tüm sürümlerde korunmalıdır.

## Derleme

GitHub Actions uygulama sürümünü `app/build.gradle.kts` içinden çözer ve çıktıları otomatik olarak:

- `BorsaTakipV5.1.29-debug.apk`
- `BorsaTakipV5.1.29-kaynak-kod.zip`

şeklinde adlandırır. Unit test, lint, debug build, release R8 smoke build ve APK sürüm/kalite kontrolleri başarıyla geçmeden sürüm tamamlandı kabul edilmez.
