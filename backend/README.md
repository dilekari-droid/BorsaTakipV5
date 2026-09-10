# BorsaTakip Production Backend

Bu servis Android uygulamasının beklediği üretim veri sözleşmesini sağlar. Servis **sahte/demo veri üretmez**; yalnızca yapılandırılmış, izinli/lisanslı upstream veri sağlayıcısından gelen veriyi normalize eder.

## Android sözleşmesi

Android tarafı şu uçları kullanır:

- `GET /v1/health`
- `GET /v1/bist/symbols`
- `GET /v1/bist/quote/{symbol}` — lisanslı anlık fiyat ve gerçek exchange timestamp
- `GET /v1/bist/history/{symbol}?range=1y&interval=1d`
- `GET /v1/preflight` — config/auth/symbol/quote/history zinciri
- `GET /v1/viop/contracts`

İstemci `BORSA_BACKEND_API_KEY` tanımlıysa `Authorization: Bearer <key>` gönderir.

## Çalıştırma

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# .env içindeki gerçek izinli/lisanslı sağlayıcı adreslerini doldurun.
set -a && source .env && set +a
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

Üretimde ters proxy / platform TLS kullanarak servisi mutlaka `https://...` altında yayınlayın. Android uygulaması HTTP backend kabul etmez.

## Docker

```bash
docker build -t borsatakip-backend .
docker run --rm -p 8080:8080 --env-file .env borsatakip-backend
```

## Upstream veri biçimi

### BIST sembolleri

`BORSA_SYMBOLS_URL` aşağıdakilerden birini döndürebilir:

```json
["ASELS", "THYAO", "EREGL"]
```

veya

```json
{"items":["ASELS", "THYAO", "EREGL"]}
```

### BIST tarihsel OHLCV

`BORSA_HISTORY_URL_TEMPLATE` içindeki `{symbol}` istenen sembolle değiştirilir. Upstream yanıtı şu biçimde olmalıdır:

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

Android analiz motoru için **en az 220 geçerli günlük mum** gerekir. Backend daha az veri gelirse `422` döndürür; veri uydurmaz. Tarihsel mum tazeliği anlık quote tazeliğiyle karıştırılmaz; gerçek zaman kontrolü quote endpointindeki `exchangeTimestamp`, `realtime`, `delaySeconds` ve `currentSessionIncluded` alanlarında yapılır.

### VİOP sözleşmeleri

`BORSA_VIOP_URL` aşağıdaki gibi bir `items` dizisi veya doğrudan dizi döndürebilir:

```json
{
  "items": [
    {
      "symbol": "XU030D...",
      "name": "BIST30 Vadeli",
      "expiry": "2026-09",
      "last": 12000.0,
      "changePct": 0.75,
      "volume": 150000,
      "openInterest": 85000,
      "dataTimestamp": 1788970000000
    }
  ]
}
```

## Sağlık kontrolü

`GET /v1/health` üretim BIST sembol ve history URL'leri HTTPS olarak yapılandırılmışsa `ok=true` verir. Bu yalnız konfigürasyon kontrolüdür; upstream servisinin o anda gerçekten veri döndürdüğünü ayrıca sembol/history çağrılarıyla doğrulayın.

## Android ayarı

Backend internette HTTPS olarak yayınlandıktan sonra uygulamada:

1. `Ayarlar` → `Üretim backend` alanına örn. `https://api.example.com` yazın.
2. `API Key` alanına `BORSA_BACKEND_API_KEY` değerini girin.
3. `BAĞLANTIYI TEST ET` ile `/v1/health` kontrolünü çalıştırın.
4. Başarılıysa `BIST Tarama` ekranını açıp taramayı başlatın.

Beklenen zincir:

```text
/v1/health -> OK
/v1/bist/symbols -> gerçek BIST evreni
/v1/bist/history/{symbol} -> >=220 günlük OHLCV
ProviderRouter -> BistScanner -> OpportunityEngine
```

## Güvenlik

- Sağlayıcı API anahtarlarını APK'ya koymayın; yalnız backend ortam değişkenlerinde tutun.
- Backend istemci anahtarını GitHub'a yazmayın.
- TradingView cookie/session aktarımı yapmayın.
- Upstream veri lisansının uygulamanın kullanım şekline izin verdiğinden emin olun.
