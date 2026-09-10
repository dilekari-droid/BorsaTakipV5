from __future__ import annotations

import math
import os
import re
import time
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote

from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel

from .provider_adapter import LicensedUpstreamProvider, ProviderError

app = FastAPI(title="BorsaTakip Backend", version="1.3.0")

API_KEY = os.getenv("BORSA_BACKEND_API_KEY", "").strip()
UPSTREAM = LicensedUpstreamProvider()
MAX_REALTIME_AGE_MS = int(os.getenv("BORSA_MAX_REALTIME_AGE_MS", "60000"))
MAX_DECLARED_DELAY_SECONDS = int(os.getenv("BORSA_MAX_DECLARED_DELAY_SECONDS", "5"))


class Health(BaseModel):
    ok: bool
    provider: str
    message: str
    timestamp: int
    strictRealtime: bool = True
    maxRealtimeAgeMs: int
    maxDeclaredDelaySeconds: int
    configurationReady: bool
    viopConfigured: bool


class SymbolsResponse(BaseModel):
    items: list[str]
    source: str
    dataTimestamp: int


class Candle(BaseModel):
    timestamp: int
    open: float
    high: float
    low: float
    close: float
    volume: float


class HistoryResponse(BaseModel):
    symbol: str
    name: str | None = None
    candles: list[Candle]
    source: str
    dataTimestamp: int
    realtime: bool
    delaySeconds: int
    currentSessionIncluded: bool


class ViopContract(BaseModel):
    symbol: str
    underlying: str
    expiry: str
    contractType: str = "Vadeli İşlem"
    lastPrice: float | None = None
    bid: float | None = None
    ask: float | None = None
    dailyChangePct: float | None = None
    tickSize: float
    multiplier: float
    openInterest: int | None = None
    volume: float | None = None
    liquidity: str | None = None
    rollover: str | None = None
    currency: str | None = None
    source: str
    dataTimestamp: int
    realtime: bool
    delaySeconds: int
    currentSessionIncluded: bool


class ViopResponse(BaseModel):
    items: list[ViopContract]
    source: str
    dataTimestamp: int


def _provider_http(exc: ProviderError) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail={"code": exc.code.value, "message": exc.message})


def _require_client_auth(authorization: str | None) -> None:
    if not API_KEY:
        # Local/dev may leave it empty. Production deployment must set it.
        return
    if authorization != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail={"code": "AUTH_ERROR", "message": "Unauthorized"})


def _normalize_symbols(payload: Any) -> list[str]:
    raw = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(raw, list):
        raise HTTPException(status_code=502, detail={"code": "INVALID_DATA", "message": "Upstream symbol response must contain an items array"})
    out: list[str] = []
    seen: set[str] = set()
    for value in raw:
        symbol = str(value).strip().upper()
        if 3 <= len(symbol) <= 12 and symbol.replace("_", "").isalnum() and symbol not in seen:
            seen.add(symbol)
            out.append(symbol)
    if not out:
        raise HTTPException(status_code=502, detail={"code": "EMPTY_DATA", "message": "Upstream returned no valid BIST symbols"})
    return out


def _require_realtime_metadata(payload: dict[str, Any]) -> tuple[int, int]:
    if payload.get("realtime") is not True:
        raise HTTPException(status_code=409, detail={"code": "NOT_REALTIME", "message": "Upstream did not certify data as real-time"})
    if payload.get("currentSessionIncluded") is not True:
        raise HTTPException(status_code=409, detail={"code": "SESSION_MISSING", "message": "Current trading session is not included in OHLCV"})
    try:
        delay_seconds = int(payload["delaySeconds"])
        data_ts = int(payload["dataTimestamp"])
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=409, detail={"code": "PROVENANCE_INCOMPLETE", "message": "Real-time provenance metadata is incomplete"}) from exc
    if delay_seconds < 0 or delay_seconds > MAX_DECLARED_DELAY_SECONDS:
        raise HTTPException(status_code=409, detail={"code": "DELAY_TOO_HIGH", "message": f"Declared provider delay is {delay_seconds}s; strict limit is {MAX_DECLARED_DELAY_SECONDS}s"})
    now = int(time.time() * 1000)
    age = now - data_ts
    if data_ts <= 0 or age > MAX_REALTIME_AGE_MS:
        raise HTTPException(status_code=409, detail={"code": "STALE_DATA", "message": f"Market data is stale; age={max(age, 0)}ms strict_limit={MAX_REALTIME_AGE_MS}ms"})
    if age < -15_000:
        raise HTTPException(status_code=409, detail={"code": "FUTURE_TIMESTAMP", "message": "Market timestamp is ahead of server clock"})
    return delay_seconds, data_ts


def _normalize_candles(payload: Any, requested_symbol: str) -> HistoryResponse:
    if not isinstance(payload, dict):
        raise HTTPException(status_code=502, detail={"code": "INVALID_DATA", "message": "Upstream history response must be an object"})
    delay_seconds, data_ts = _require_realtime_metadata(payload)
    rows = payload.get("candles")
    if not isinstance(rows, list):
        raise HTTPException(status_code=502, detail={"code": "HISTORY_ERROR", "message": "Upstream history response must contain candles"})
    candles: list[Candle] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        try:
            candle = Candle(
                timestamp=int(row["timestamp"]),
                open=float(row["open"]),
                high=float(row["high"]),
                low=float(row["low"]),
                close=float(row["close"]),
                volume=float(row["volume"]),
            )
        except (KeyError, TypeError, ValueError):
            continue
        if not all(math.isfinite(v) for v in [candle.open, candle.high, candle.low, candle.close, candle.volume]):
            continue
        if candle.timestamp <= 0 or candle.high < candle.low or candle.close <= 0 or candle.volume < 0:
            continue
        candles.append(candle)
    candles.sort(key=lambda c: c.timestamp)
    if len(candles) < 220:
        raise HTTPException(status_code=422, detail={"code": "INSUFFICIENT_HISTORY", "message": f"Insufficient OHLCV history: {len(candles)} candles; minimum 220"})
    symbol = str(payload.get("symbol") or requested_symbol).strip().upper()
    name = payload.get("name")
    return HistoryResponse(
        symbol=symbol,
        name=str(name).strip() if name else None,
        candles=candles,
        source=UPSTREAM.name,
        dataTimestamp=data_ts,
        realtime=True,
        delaySeconds=delay_seconds,
        currentSessionIncluded=True,
    )


def _finite_optional(value: Any, *, positive: bool = False, nonnegative: bool = False) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(number):
        return None
    if positive and number <= 0:
        return None
    if nonnegative and number < 0:
        return None
    return number


def _expiry_is_current_or_future(expiry: str) -> bool:
    if not re.fullmatch(r"\d{4}-\d{2}", expiry):
        return False
    try:
        year, month = [int(x) for x in expiry.split("-")]
        if month < 1 or month > 12:
            return False
        now = datetime.now(timezone.utc)
        return (year, month) >= (now.year, now.month)
    except ValueError:
        return False


def _normalize_viop_row(row: dict[str, Any]) -> ViopContract | None:
    symbol = str(row.get("symbol") or "").strip().upper()
    underlying = str(row.get("underlying") or "").strip().upper()
    expiry = str(row.get("expiry") or "").strip()
    if not symbol or not underlying or not _expiry_is_current_or_future(expiry):
        return None
    tick_size = _finite_optional(row.get("tickSize"), positive=True)
    multiplier = _finite_optional(row.get("multiplier"), positive=True)
    if tick_size is None or multiplier is None:
        return None
    try:
        delay_seconds = int(row["delaySeconds"])
        data_ts = int(row["dataTimestamp"])
    except (KeyError, TypeError, ValueError):
        return None
    if row.get("realtime") is not True or row.get("currentSessionIncluded") is not True:
        return None
    if delay_seconds < 0 or delay_seconds > MAX_DECLARED_DELAY_SECONDS:
        return None
    now = int(time.time() * 1000)
    age = now - data_ts
    if data_ts <= 0 or age > MAX_REALTIME_AGE_MS or age < -15_000:
        return None
    open_interest: int | None = None
    if row.get("openInterest") is not None:
        try:
            parsed = int(row["openInterest"])
            if parsed >= 0:
                open_interest = parsed
        except (TypeError, ValueError):
            pass
    return ViopContract(
        symbol=symbol,
        underlying=underlying,
        expiry=expiry,
        contractType=str(row.get("contractType") or "Vadeli İşlem").strip() or "Vadeli İşlem",
        lastPrice=_finite_optional(row.get("lastPrice", row.get("last")), positive=True),
        bid=_finite_optional(row.get("bid"), nonnegative=True),
        ask=_finite_optional(row.get("ask"), nonnegative=True),
        dailyChangePct=_finite_optional(row.get("dailyChangePct", row.get("changePct"))),
        tickSize=tick_size,
        multiplier=multiplier,
        openInterest=open_interest,
        volume=_finite_optional(row.get("volume"), nonnegative=True),
        liquidity=str(row.get("liquidity")).strip() if row.get("liquidity") else None,
        rollover=str(row.get("rollover")).strip() if row.get("rollover") else None,
        currency=str(row.get("currency")).strip() if row.get("currency") else None,
        source=str(row.get("source") or UPSTREAM.name).strip() or UPSTREAM.name,
        dataTimestamp=data_ts,
        realtime=True,
        delaySeconds=delay_seconds,
        currentSessionIncluded=True,
    )


@app.get("/v1/health", response_model=Health)
async def health(authorization: str | None = Header(default=None)) -> Health:
    _require_client_auth(authorization)
    configured = UPSTREAM.configuration_ready()
    return Health(
        ok=configured,
        provider=UPSTREAM.name if configured else "unconfigured",
        message="Licensed HTTPS upstream configured" if configured else "Licensed BIST upstream endpoints are not configured",
        timestamp=int(time.time() * 1000),
        maxRealtimeAgeMs=MAX_REALTIME_AGE_MS,
        maxDeclaredDelaySeconds=MAX_DECLARED_DELAY_SECONDS,
        configurationReady=configured,
        viopConfigured=UPSTREAM.viop_url.startswith("https://"),
    )


@app.get("/v1/bist/symbols", response_model=SymbolsResponse)
async def bist_symbols(authorization: str | None = Header(default=None)) -> SymbolsResponse:
    _require_client_auth(authorization)
    try:
        payload = await UPSTREAM.get_json(UPSTREAM.symbols_url)
    except ProviderError as exc:
        raise _provider_http(exc) from exc
    items = _normalize_symbols(payload)
    return SymbolsResponse(items=items, source=UPSTREAM.name, dataTimestamp=int(time.time() * 1000))


@app.get("/v1/bist/history/{symbol}", response_model=HistoryResponse)
async def bist_history(
    symbol: str,
    range: str = Query(default="1y"),
    interval: str = Query(default="1d"),
    authorization: str | None = Header(default=None),
) -> HistoryResponse:
    _require_client_auth(authorization)
    normalized = symbol.strip().upper()
    if not (3 <= len(normalized) <= 12 and normalized.replace("_", "").isalnum()):
        raise HTTPException(status_code=400, detail={"code": "INVALID_SYMBOL", "message": "Invalid symbol"})
    if range != "1y" or interval != "1d":
        raise HTTPException(status_code=400, detail={"code": "UNSUPPORTED_RANGE", "message": "Only range=1y&interval=1d is supported by the mobile contract"})
    if "{symbol}" not in UPSTREAM.history_url_template:
        raise HTTPException(status_code=503, detail={"code": "NOT_CONFIGURED", "message": "BORSA_HISTORY_URL_TEMPLATE must contain {symbol}"})
    url = UPSTREAM.history_url_template.replace("{symbol}", quote(normalized, safe=""))
    try:
        payload = await UPSTREAM.get_json(url)
    except ProviderError as exc:
        raise _provider_http(exc) from exc
    return _normalize_candles(payload, normalized)


@app.get("/v1/viop/contracts", response_model=ViopResponse)
async def viop_contracts(authorization: str | None = Header(default=None)) -> ViopResponse:
    _require_client_auth(authorization)
    if not UPSTREAM.viop_url:
        raise HTTPException(status_code=503, detail={"code": "NOT_CONFIGURED", "message": "VIOP upstream is not configured"})
    try:
        payload = await UPSTREAM.get_json(UPSTREAM.viop_url)
    except ProviderError as exc:
        raise _provider_http(exc) from exc
    rows = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(rows, list):
        raise HTTPException(status_code=502, detail={"code": "INVALID_DATA", "message": "Upstream VIOP response must contain an items array"})
    items = [_normalize_viop_row(row) for row in rows if isinstance(row, dict)]
    valid_items = [item for item in items if item is not None]
    if not valid_items:
        raise HTTPException(status_code=422, detail={"code": "INVALID_DATA", "message": "No VIOP contract passed expiry, parameter and real-time provenance validation"})
    return ViopResponse(items=valid_items, source=UPSTREAM.name, dataTimestamp=max(item.dataTimestamp for item in valid_items))
