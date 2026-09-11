# BorsaTakip Production Backend

Bu servis Android uygulamasının beklediği üretim veri sözleşmesini sağlar. Servis **sahte/demo veri üretmez**; yalnızca yapılandırılmış, izinli/lisanslı upstream veri sağlayıcısından gelen veriyi normalize eder.

## Android sözleşmesi

Android tarafı şu uçları kullanır:

- `GET /v1/health`
- `GET /v1/preflight`
- `GET /v1/bist/symbols`
- `GET /v1/bist/quote/{symbol}`
- `GET /v1/bist/history/{symbol}?range=1y&interval=1d`
- `GET /v1/viop/contracts`
- `GET /v1/viop/quote/{symbol}`
- `GET /v1/viop/history/{symbol}?range=1y&interval=1d`

İstemci, `/v1/health` dışındaki protected uçlara `Authorization: Bearer <BORSA_BACKEND_API_KEY>` gönderir.

## Health ve readiness ayrımı

`GET /v1/health` servis prosesinin ayakta olduğunu bildirir ve aşağıdaki alanlarla yapılandırma durumunu ayrıca açıklar:

- `configurationReady`: BIST symbols + quote + history upstream sözleşmeleri eksiksiz ve HTTPS mi?
- `authenticationConfigured`: Android istemci kimlik doğrulama anahtarı yapılandırılmış mı?
- `viopConfigured`: VİOP contracts + quote + history upstream sözleşmeleri eksiksiz ve HTTPS mi?

`health.ok=true` tek başına BIST/VİOP provider READY anlamına gelmez. Android üretim readiness kararı `GET /v1/preflight` ve ayrı VİOP contracts/quote/history kontrollerinin tamamı geçtikten sonra verilir.

## Zorunlu deployment değişkenleri

Gerçek Production BIST için:

- `BORSA_BACKEND_API_KEY`
- `BORSA_UPSTREAM_NAME`
- `BORSA_SYMBOLS_URL`
- `BORSA_QUOTE_URL_TEMPLATE`
- `BORSA_HISTORY_URL_TEMPLATE`
- `BORSA_UPSTREAM_TOKEN` (sağlayıcı gerektiriyorsa)

Gerçek Production VİOP için bunlara ek olarak:

- `BORSA_VIOP_URL`
- `BORSA_VIOP_QUOTE_URL_TEMPLATE`
- `BORSA_VIOP_HISTORY_URL_TEMPLATE`

Bütün upstream URL'leri gerçek lisanslı/izinli `https://` endpointleri olmalıdır. Quote/history template'leri `{symbol}` yer tutucusu içermelidir. Gerçek secret veya provider endpointleri source control'e yazılmaz.

## Çalıştırma

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# .env içindeki gerçek izinli/lisanslı sağlayıcı adreslerini ve deployment secret'larını doldurun.
set -a && source .env && set +a
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

Üretimde ters proxy / platform TLS kullanarak servisi mutlaka `https://...` altında yayınlayın. Android uygulaması HTTP backend kabul etmez.

## Docker

```bash
docker build -t borsatakip-backend .
docker run --rm -p 8080:8080 --env-file .env borsatakip-backend
```

## Upstream veri biçimleri

### BIST sembolleri

`BORSA_SYMBOLS_URL` aşağıdakilerden birini döndürebilir:

```json
["ASELS", "THYAO", "EREGL"]
```

veya

```json
{"items":["ASELS", "THYAO", "EREGL"]}
```

### BIST / VİOP realtime quote

`BORSA_QUOTE_URL_TEMPLATE` ve `BORSA_VIOP_QUOTE_URL_TEMPLATE` içindeki `{symbol}` istenen sembolle değiştirilir. Yanıt en az şu provenance alanlarını sağlamalıdır:

```json
{
  "symbol": "ASELS",
  "price": 102.0,
  "exchangeTimestamp": 1788970000000,
  "realtime": true,
  "currentSessionIncluded": true,
  "delaySeconds": 0,
  "source": "licensed-provider"
}
```

Backend `realtime=true`, `currentSessionIncluded=true`, kabul edilebilir `delaySeconds` ve taze `exchangeTimestamp` olmadan veriyi REALTIME kabul etmez.

### BIST / VİOP tarihsel OHLCV

`BORSA_HISTORY_URL_TEMPLATE` ve `BORSA_VIOP_HISTORY_URL_TEMPLATE` içindeki `{symbol}` istenen sembolle değiştirilir. Upstream yanıtı şu biçimde olmalıdır:

```json
{
  "symbol": "ASELS",
  "name": "ASELSAN",
  "dataTimestamp": 1788970000000,
  "candles": [
    {
      "timestamp": 1757376000000,
      "open": 100.0,
      "high": 103.0,
      "low": 99.0,
      "close": 102.0,
      "volume": 1234567
    }
  ]
}
```

Android analiz motoru için **en az 220 geçerli günlük mum** gerekir. Backend daha az veri gelirse `422 INSUFFICIENT_HISTORY` döndürür; veri uydurmaz.

### VİOP sözleşmeleri

`BORSA_VIOP_URL` bir `items` dizisi veya doğrudan dizi döndürebilir. Her aktif sözleşmede en az aşağıdaki alanlar beklenir:

```json
{
  "items": [
    {
      "symbol": "XU030D...",
      "underlying": "XU030",
      "expiry": "2026-09",
      "tickSize": 0.25,
      "multiplier": 10,
      "lastPrice": 12000.0,
      "volume": 150000,
      "openInterest": 85000,
      "dataTimestamp": 1788970000000,
      "realtime": true,
      "currentSessionIncluded": true,
      "delaySeconds": 0
    }
  ]
}
```

Süresi geçmiş, realtime olmayan, current-session içermeyen veya provenance/tick/multiplier doğrulamasını geçmeyen sözleşmeler yayınlanmaz.

## Production doğrulama zinciri

Android tarafında gerçek provider READY kabulü için zincir:

```text
HTTPS backend
  -> /v1/health
  -> Authentication
  -> /v1/preflight
      -> BIST symbols
      -> BIST quote
      -> BIST history (>=220 mum)
  -> /v1/viop/contracts
  -> /v1/viop/quote/{symbol}
  -> /v1/viop/history/{symbol} (>=220 mum)
  -> PROVIDER_READY
```

Bu zincirde herhangi bir adım başarısızsa Production tarama başlamamalıdır.

## Android ayarı

Backend internette HTTPS olarak yayınlandıktan sonra uygulamada:

1. `Ayarlar` → `Üretim backend` alanına gerçek HTTPS adresini girin.
2. `API Key` alanına `BORSA_BACKEND_API_KEY` değerini girin.
3. `BAĞLANTIYI TEST ET` ile readiness zincirini çalıştırın.
4. Yalnız tüm BIST ve VİOP kontrolleri geçip `PROVIDER_READY` görüldüğünde Production taramayı başlatın.

## Güvenlik

- Sağlayıcı API anahtarlarını APK'ya koymayın; yalnız backend ortam değişkenlerinde tutun.
- Backend istemci anahtarını GitHub'a yazmayın.
- Production secret'larını loglamayın.
- TLS zorunludur; HTTP upstream/backend kabul edilmez.
- TradingView cookie/session aktarımı yapmayın.
- Yahoo/deneysel veri VİOP Production kaynağı olarak kullanılmaz.
- Upstream veri lisansının uygulamanın kullanım şekline izin verdiğinden emin olun.

## Gerçek Production kabul kriteri

Source/build testlerinin geçmesi tek başına Production başarısı değildir. Aşağıdaki adımlar gerçek deployment ve gerçek piyasa verisiyle doğrulanmadan sistem tamamlanmış kabul edilmez:

- Health ✓
- Authentication ✓
- BIST Symbols ✓
- BIST Quote ✓
- BIST History ✓
- VİOP Contracts ✓
- VİOP Quote ✓
- VİOP History ✓
- Fiziksel cihaz taraması ✓

Bu bilgiler yoksa doğru durum `BLOCKED / NOT CONFIGURED` olmalıdır; örnek URL, secret veya sahte piyasa verisi uydurulmaz.
