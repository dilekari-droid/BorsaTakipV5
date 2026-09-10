# BORSA TAKİP V5.1.27

Android/Kotlin tabanlı BIST ve VİOP takip uygulaması.

## Veri mimarisi

Uygulama tek bir veri sağlayıcısına kilitlenmez ve gerçek BIST verisini TradingView scraping/session trafiğinden almaz.

- `MarketDataProvider`: Android ortak sağlayıcı sözleşmesi
- `MobileMarketDataProvider`: ana BIST veri sağlayıcısı; gerçek HTTPS BorsaTakip Backend'e bağlanır
- `BackendProvider`: VİOP backend bağlantısı
- `ProviderRouter`: ana sağlayıcıyı kullanır; Yahoo yalnız kullanıcı deneysel yedeği açıkça etkinleştirdiyse devreye girebilir
- `YahooFallbackProvider`: yalnız yedek/gecikmeli kaynaktır; REALTIME olarak etiketlenmez
- `BackendPreflightClient`: Health → Authentication → Symbols → History zincirini doğrular
- `LicensedUpstreamProvider` (backend): lisanslı/izinli gerçek veri sağlayıcısına adapter katmanı
- `LiveMarketSocket`: HTTPS backend adresinden türetilen `wss://.../v1/live` sözleşmesine bağlanmak için istemci altyapısı

Ana backend sözleşmeleri:

- `GET /v1/health`
- `GET /v1/bist/symbols`
- `GET /v1/bist/history/{symbol}?range=1y&interval=1d`
- `GET /v1/viop/contracts`

BIST evreni APK içine sabitlenmez. Gerçek sembol listesi production backend üzerinden dinamik alınır. Backend hazır değilse tarama başlamaz ve `0/0 = fırsat yok` gibi yanıltıcı bir durum üretilmez.

## Production backend

Backend `backend/` altında FastAPI ile bulunur. Kaynak kod sahte/demo piyasa verisi üretmez. Gerçek production çalışması için deployment ortamında aşağıdaki değerler gerekir:

- `BORSA_BACKEND_API_KEY`
- `BORSA_UPSTREAM_NAME`
- `BORSA_SYMBOLS_URL` — gerçek, lisanslı/izinli HTTPS endpoint
- `BORSA_HISTORY_URL_TEMPLATE` — gerçek, lisanslı/izinli HTTPS endpoint template'i ve `{symbol}` yer tutucusu
- `BORSA_UPSTREAM_TOKEN` — gerekiyorsa yalnız backend secret olarak
- `BORSA_VIOP_URL` — VİOP kullanılacaksa gerçek endpoint

Gerçek endpoint/credential repoda bulunmadığı sürece production veri entegrasyonu **BLOCKED** kabul edilir. `provider.example` veya başka örnek domain gerçek servis gibi kullanılmaz.

Backend adapter'ı geçici transport hatalarında en fazla 3 denemeli kontrollü retry uygular. DNS/TLS/timeout/auth/HTTP/invalid JSON/empty data durumlarını ayırır. Realtime veri, `realtime=true`, `currentSessionIncluded=true`, kabul edilebilir `delaySeconds` ve taze `dataTimestamp` koşulları sağlanmadan güçlü fırsat olarak yayınlanmaz.

## TradingView

TradingView yalnız grafik/datafeed katmanı olarak konumlandırılır. Scraping, gizli API, cookie/session kopyalama, tersine mühendislik veya gizli WebSocket trafiği kullanılmaz. Grafik verisinin kaynağı lisanslı upstream → BorsaTakip Backend zinciridir.

## Teknik özellikler

- Koyu lacivert mobil arayüz
- BIST tarama ve gerçek sembol ilerlemesi
- EMA20/50/200, Wilder RSI14, MACD, Bollinger, ATR, VWAP
- Fırsat skoru, risk skoru, LONG/SHORT ve 85+ yerel filtreleri
- `DataMode`, `SignalValidity`, `ScanRun`, `LastSuccessfulScanStore`, `SignalHistoryStore`
- Gerçek OHLCV yoksa grafik uydurmama
- VİOP için ayrı sözleşme ve provenance doğrulaması
- Favoriler ve fırsat bildirim altyapısı
- HTTPS zorunlu ana veri servisi
- İsteğe bağlı Yahoo gecikmeli yedek kaynak

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
- backend Docker build smoke testi

Başarılı build, gerçek production provider'ın çalıştığı anlamına gelmez. Nihai kabul için telefonda gerçek backend URL'siyle `Health ✓ → Authentication ✓ → Symbols ✓ → History ✓`, `symbolCount > 0` ve ardından gerçek N sembol üzerinden tarama görülmelidir.

## Release imzası

Release anahtarı repoya eklenmez. GitHub Actions aşağıdaki repository secret'ları tanımlıysa kalıcı release keystore ile imzalı APK üretir:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Bu secret'lar yoksa workflow yalnız debug APK üretir.

## Derleme

Android:

```bash
gradle clean
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

Backend:

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
pytest -q tests
docker build -t borsatakip-backend .
```

Production deployment için gerçek lisanslı upstream endpoint/credential ve HTTPS platform konfigürasyonu ayrıca gereklidir.
