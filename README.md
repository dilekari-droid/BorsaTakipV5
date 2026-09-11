# BORSA TAKİP V5.1.27

Android/Kotlin tabanlı BIST ve VİOP takip uygulaması.

## Veri mimarisi

Uygulama tek bir veri sağlayıcısına kilitlenmez ve gerçek BIST/VİOP verisini TradingView scraping/session trafiğinden almaz.

- `MarketDataProvider`: Android ortak sağlayıcı sözleşmesi
- `MobileMarketDataProvider`: ana BIST veri sağlayıcısı; gerçek HTTPS BorsaTakip Backend'e bağlanır
- `BackendProvider`: VİOP backend bağlantısı
- `ProviderRouter`: ana sağlayıcıyı kullanır; Yahoo yalnız kullanıcı deneysel yedeği açıkça etkinleştirdiyse devreye girebilir
- `YahooFallbackProvider`: yalnız yedek/gecikmeli kaynaktır; REALTIME olarak etiketlenmez
- `BackendPreflightClient`: Health → Authentication → BIST Symbols → BIST Quote → BIST History → VİOP Contracts → VİOP Quote → VİOP History zincirini doğrular
- `ProviderReadinessService`: merkezi `PROVIDER_NOT_CONFIGURED / CONFIGURED / TESTING / READY / ERROR` durumunu yönetir
- `LicensedUpstreamProvider` (backend): lisanslı/izinli gerçek veri sağlayıcısına adapter katmanı
- `LiveMarketSocket`: HTTPS backend adresinden türetilen `wss://.../v1/live` sözleşmesine bağlanmak için istemci altyapısı

Ana backend sözleşmeleri:

- `GET /v1/health`
- `GET /v1/preflight`
- `GET /v1/bist/symbols`
- `GET /v1/bist/quote/{symbol}`
- `GET /v1/bist/history/{symbol}?range=1y&interval=1d`
- `GET /v1/viop/contracts`
- `GET /v1/viop/quote/{symbol}`
- `GET /v1/viop/history/{symbol}?range=1y&interval=1d`

BIST/VİOP evreni APK içine sabitlenmez. Gerçek sembol ve sözleşme listeleri production backend üzerinden dinamik alınır. Backend hazır değilse tarama başlamaz ve `0/0 = fırsat yok` gibi yanıltıcı bir durum üretilmez.

## Production backend

Backend `backend/` altında FastAPI ile bulunur. Kaynak kod sahte/demo piyasa verisi üretmez. Gerçek production çalışması için deployment ortamında aşağıdaki değerler gerekir:

- `BORSA_BACKEND_API_KEY`
- `BORSA_UPSTREAM_NAME`
- `BORSA_SYMBOLS_URL`
- `BORSA_QUOTE_URL_TEMPLATE`
- `BORSA_HISTORY_URL_TEMPLATE`
- `BORSA_UPSTREAM_TOKEN` — gerekiyorsa yalnız backend secret olarak
- `BORSA_VIOP_URL`
- `BORSA_VIOP_QUOTE_URL_TEMPLATE`
- `BORSA_VIOP_HISTORY_URL_TEMPLATE`

Gerçek endpoint/credential repoda bulunmadığı sürece production veri entegrasyonu **BLOCKED** kabul edilir. Örnek/uydurma domain gerçek servis gibi kullanılmaz.

Backend adapter'ı geçici transport hatalarında en fazla 3 denemeli kontrollü retry uygular. DNS/TLS/timeout/auth/HTTP/invalid JSON/empty data durumlarını ayırır. Realtime veri, `realtime=true`, `currentSessionIncluded=true`, kabul edilebilir `delaySeconds` ve taze `exchangeTimestamp` koşulları sağlanmadan güçlü fırsat olarak yayınlanmaz.

`/v1/health` prosesin ayakta olduğunu bildirir; bu tek başına provider READY demek değildir. Gerçek readiness için `configurationReady`, `authenticationConfigured`, `viopConfigured` alanları ve Android tarafındaki tam preflight zinciri birlikte değerlendirilir.

## TradingView

TradingView yalnız grafik/datafeed katmanı olarak konumlandırılır. Scraping, gizli API, cookie/session kopyalama, tersine mühendislik veya gizli WebSocket trafiği kullanılmaz. Grafik verisinin kaynağı lisanslı upstream → BorsaTakip Backend zinciridir.

## Teknik özellikler

- Koyu lacivert mobil arayüz
- BIST tarama ve gerçek sembol ilerlemesi
- VİOP aktif sözleşme → quote → history → teknik analiz akışı
- EMA20/50/200, Wilder RSI14, MACD, Bollinger, ATR, VWAP
- Fırsat skoru, risk skoru, LONG/SHORT ve 85+ yerel filtreleri
- `DataMode`, `SignalValidity`, `ScanRun`, `LastSuccessfulScanStore`, `SignalHistoryStore`
- Merkezi `HistoryRecorder` ile ekran-bağımsız sinyal geçmişi kaydı
- Gerçek OHLCV yoksa grafik uydurmama
- VİOP için ayrı sözleşme ve provenance doğrulaması
- Favoriler ve fırsat bildirim altyapısı
- HTTPS zorunlu ana veri servisi
- İsteğe bağlı Yahoo gecikmeli yedek kaynak; VİOP Production kaynağı değildir

## Test ve CI

GitHub Actions:

- Android unit test
- Android lint
- debug APK build
- debug APK imza doğrulaması
- release R8 smoke build
- forbidden legacy TradingView/WebView auth kontrolü
- `connectedDebugAndroidTest` runtime verifier regresyonu
- backend Python compile + contract/provenance testleri
- backend README / route sözleşmesi senkronizasyon kontrolü
- `.env.example` Production BIST/VİOP değişken kontrolü
- backend Docker build smoke testi
- kaynak teslim paketine doğrulanmış Gradle 8.9 Wrapper üretimi

Başarılı build, gerçek production provider'ın çalıştığı anlamına gelmez. Nihai kabul için telefonda gerçek backend URL'siyle şu zincir görülmelidir:

`Health ✓ → Authentication ✓ → BIST Symbols ✓ → BIST Quote ✓ → BIST History ✓ → VİOP Contracts ✓ → VİOP Quote ✓ → VİOP History ✓ → PROVIDER_READY`

Ardından gerçek N sembol / N sözleşme üzerinden tarama doğrulanmalıdır.

## Release imzası

Release anahtarı repoya eklenmez. GitHub Actions aşağıdaki repository secret'ları tanımlıysa kalıcı release keystore ile imzalı APK üretir:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Bu secret'lar yoksa workflow yalnız debug APK üretir. Release signing secret'ları olmadan dağıtıma hazır release APK üretilmiş sayılmaz.

## Derleme

Git deposunda binary `gradle-wrapper.jar` tutulmaz. Bunun yerine GitHub Actions, doğrulanmış Gradle 8.9 ile `gradle wrapper --gradle-version 8.9` çalıştırır ve teslim edilen `BorsaTakipV5.1.27-source.zip` paketine `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` ve `gradle-wrapper.properties` dosyalarını ekler. Böylece teslim kaynak paketi yerel olarak wrapper ile yeniden derlenebilir.

CI kaynağından indirilen teslim ZIP'i ile:

```bash
./gradlew --version      # Gradle 8.9
./gradlew clean
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Repository checkout'u doğrudan kullanılıyorsa sistem Gradle 8.9 ile aynı komutlar `gradle ...` biçiminde çalıştırılabilir.

Backend:

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
pytest -q tests
docker build -t borsatakip-backend .
```

Production deployment için gerçek lisanslı upstream endpoint/credential ve HTTPS platform konfigürasyonu ayrıca gereklidir. Bu değerler yoksa uygulamanın doğru durumu `BLOCKED / NOT CONFIGURED` olmalıdır.
