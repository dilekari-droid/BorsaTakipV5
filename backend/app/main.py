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

app = FastAPI(title="BorsaTakip Backend", version="1.4.0")

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
    authenticationConfigured: bool
    viopConfigured: bool


class SymbolsResponse(BaseModel):
    items: list[str]
    source: str
    dataTimestamp: int


class QuoteResponse(BaseModel):
    symbol: str
    price: float
    bid: float | None = None
    ask: float | None = None
    currency: str | None = None
    exchangeTimestamp: int
    receivedAt: int
    source: str
    providerId: str
    realtime: bool
    currentSessionIncluded: bool
    delaySeconds: int
    dataMode: str = "REALTIME"


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
    candleCount: int
    dataMode: str = "HISTORICAL"


class PreflightCheck(BaseModel):
    ok: bool
    code: str
    message: str


class PreflightResponse(BaseModel):
    ok: bool
    provider: str
    symbolCount: int = 0
    sampleSymbol: str | None = None
    backendConfig: PreflightCheck
    authentication: PreflightCheck
    symbols: PreflightCheck
    quote: PreflightCheck
    history: PreflightCheck


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


def _auth_configured() -> bool:
    return bool(API_KEY and API_KEY != "CHANGE_ME_IN_DEPLOYMENT")


def _require_client_auth(authorization: str | None) -> None:
    if not _auth_configured():
        raise HTTPException(status_code=503, detail={"code": "AUTH_NOT_CONFIGURED", "message": "Production client authentication is not configured"})
    if authorization != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail={"code": "AUTH_ERROR", "message": "Unauthorized"})


def _normalize_symbol(symbol: str) -> str:
    normalized = symbol.strip().upper()
    if not (3 <= len(normalized) <= 12 and normalized.replace("_", "").isalnum()):
        raise HTTPException(status_code=400, detail={"code": "INVALID_SYMBOL", "message": "Invalid symbol"})
    return normalized


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
        raise HTTPException(status_code=409, detail={"code": "SESSION_MISSING", "message": "Current trading session is not included"})
    try:
        delay_seconds = int(payload["delaySeconds"])
        exchange_ts = int(payload.get("exchangeTimestamp", payload.get("dataTimestamp")))
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=409, detail={"code": "PROVENANCE_INCOMPLETE", "message": "Real-time provenance metadata is incomplete"}) from exc
    if delay_seconds < 0 or delay_seconds > MAX_DECLARED_DELAY_SECONDS:
        raise HTTPException(status_code=409, detail={"code": "DELAY_TOO_HIGH", "message": f"Declared provider delay is {delay_seconds}s; strict limit is {MAX_DECLARED_DELAY_SECONDS}s"})
    now = int(time.time() * 1000)
    age = now - exchange_ts
    if exchange_ts <= 0 or age > MAX_REALTIME_AGE_MS:
        raise HTTPException(status_code=409, detail={"code": "STALE_DATA", "message": f"Market data is stale; age={max(age, 0)}ms strict_limit={MAX_REALTIME_AGE_MS}ms"})
    if age < -15_000:
        raise HTTPException(status_code=409, detail={"code": "FUTURE_TIMESTAMP", "message": "Market timestamp is ahead of server clock"})
    return delay_seconds, exchange_ts


def _normalize_quote(payload: Any, requested_symbol: str) -> QuoteResponse:
    if not isinstance(payload, dict):
        raise HTTPException(status_code=502, detail={"code": "INVALID_DATA", "message": "Upstream quote response must be an object"})
    delay_seconds, exchange_ts = _require_realtime_metadata(payload)
    try:
        price = float(payload.get("price", payload.get("last")))
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail={"code": "QUOTE_ERROR", "message": "Quote price is missing or invalid"}) from exc
    if not math.isfinite(price) or price <= 0:
        raise HTTPException(status_code=502, detail={"code": "QUOTE_ERROR", "message": "Quote price must be finite and positive"})
    symbol = str(payload.get("symbol") or requested_symbol).strip().upper()
    return QuoteResponse(
        symbol=symbol,
        price=price,
        bid=_finite_optional(payload.get("bid"), nonnegative=True),
        ask=_finite_optional(payload.get("ask"), nonnegative=True),
        currency=str(payload.get("currency")).strip() if payload.get("currency") else None,
        exchangeTimestamp=exchange_ts,
        receivedAt=int(time.time() * 1000),
        source=str(payload.get("source") or UPSTREAM.name).strip() or UPSTREAM.name,
        providerId=str(payload.get("providerId") or UPSTREAM.name).strip() or UPSTREAM.name,
        realtime=True,
        currentSessionIncluded=True,
        delaySeconds=delay_seconds,
        dataMode="REALTIME",
    )


def _normalize_candles(payload: Any, requested_symbol: str) -> HistoryResponse:
    if not isinstance(payload, dict):
        raise HTTPException(status_code=502, detail={"code": "INVALID_DATA", "message": "Upstream history response must be an object"})
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
    data_ts = int(payload.get("dataTimestamp") or candles[-1].timestamp)
    return HistoryResponse(
        symbol=symbol,
        name=str(name).strip() if name else None,
        candles=candles,
        source=str(payload.get("source") or UPSTREAM.name).strip() or UPSTREAM.name,
        dataTimestamp=data_ts,
        candleCount=len(candles),
        dataMode=str(payload.get("dataMode") or "HISTORICAL").upper(),
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


async def _symbols_impl() -> SymbolsResponse:
    try:
        payload = await UPSTREAM.get_json(UPSTREAM.symbols_url)
    except ProviderError as exc:
        raise _provider_http(exc) from exc
    items = _normalize_symbols(payload)
    return SymbolsResponse(items=items, source=UPSTREAM.name, dataTimestamp=int(time.time() * 1000))


async def _quote_impl(symbol: str) -> QuoteResponse:
    normalized = _normalize_symbol(symbol)
    if "{symbol}" not in UPSTREAM.quote_url_template:
        raise HTTPException(status_code=503, detail={"code": "NOT_CONFIGURED", "message": "BORSA_QUOTE_URL_TEMPLATE must contain {symbol}"})
    url = UPSTREAM.quote_url_template.replace("{symbol}", quote(normalized, safe=""))
    try:
        payload = await UPSTREAM.get_json(url)
    except ProviderError as exc:
        raise _provider_http(exc) from exc
    return _normalize_quote(payload, normalized)


async def _history_impl(symbol: str) -> HistoryResponse:
    normalized = _normalize_symbol(symbol)
    if "{symbol}" not in UPSTREAM.history_url_template:
        raise HTTPException(status_code=503, detail={"code": "NOT_CONFIGURED", "message": "BORSA_HISTORY_URL_TEMPLATE must contain {symbol}"})
    url = UPSTREAM.history_url_template.replace("{symbol}", quote(normalized, safe=""))
    try:
        payload = await UPSTREAM.get_json(url)
    except ProviderError as exc:
        raise _provider_http(exc) from exc
    return _normalize_candles(payload, normalized)


@app.get("/v1/health", response_model=Health)
async def health() -> Health:
    configured = UPSTREAM.configuration_ready()
    auth_ready = _auth_configured()
    return Health(
        ok=True,
        provider=UPSTREAM.name if configured else "unconfigured",
        message="Backend process is healthy" if configured and auth_ready else "Backend process is healthy but production configuration is incomplete",
        timestamp=int(time.time() * 1000),
        maxRealtimeAgeMs=MAX_REALTIME_AGE_MS,
        maxDeclaredDelaySeconds=MAX_DECLARED_DELAY_SECONDS,
        configurationReady=configured,
        authenticationConfigured=auth_ready,
        viopConfigured=UPSTREAM.viop_url.startswith("https://"),
    )


@app.get("/v1/preflight", response_model=PreflightResponse)
async def preflight(authorization: str | None = Header(default=None)) -> PreflightResponse:
    config_ok = UPSTREAM.configuration_ready()
    backend_check = PreflightCheck(ok=config_ok, code="OK" if config_ok else "PRODUCTION_BACKEND_NOT_CONFIGURED", message="Licensed symbols/quote/history endpoints configured" if config_ok else "Licensed symbols/quote/history endpoints are incomplete")
    auth_ok = _auth_configured() and authorization == f"Bearer {API_KEY}"
    auth_check = PreflightCheck(ok=auth_ok, code="OK" if auth_ok else ("AUTH_NOT_CONFIGURED" if not _auth_configured() else "AUTH_ERROR"), message="Client authentication verified" if auth_ok else "Client authentication failed or is not configured")
    if not config_ok or not auth_ok:
        return PreflightResponse(ok=False, provider=UPSTREAM.name, backendConfig=backend_check, authentication=auth_check, symbols=PreflightCheck(ok=False, code="BLOCKED", message="Blocked by configuration/authentication"), quote=PreflightCheck(ok=False, code="BLOCKED", message="Blocked by configuration/authentication"), history=PreflightCheck(ok=False, code="BLOCKED", message="Blocked by configuration/authentication"))

    try:
        symbols = await _symbols_impl()
        symbols_check = PreflightCheck(ok=True, code="OK", message=f"{len(symbols.items)} symbols received")
        sample = symbols.items[0]
    except HTTPException as exc:
        return PreflightResponse(ok=False, provider=UPSTREAM.name, backendConfig=backend_check, authentication=auth_check, symbols=PreflightCheck(ok=False, code="SYMBOLS_ERROR", message=str(exc.detail)), quote=PreflightCheck(ok=False, code="BLOCKED", message="Symbols failed"), history=PreflightCheck(ok=False, code="BLOCKED", message="Symbols failed"))

    try:
        await _quote_impl(sample)
        quote_check = PreflightCheck(ok=True, code="OK", message=f"Live quote verified for {sample}")
    except HTTPException as exc:
        return PreflightResponse(ok=False, provider=UPSTREAM.name, symbolCount=len(symbols.items), sampleSymbol=sample, backendConfig=backend_check, authentication=auth_check, symbols=symbols_check, quote=PreflightCheck(ok=False, code="QUOTE_ERROR", message=str(exc.detail)), history=PreflightCheck(ok=False, code="BLOCKED", message="Quote failed"))

    try:
        history = await _history_impl(sample)
        history_check = PreflightCheck(ok=True, code="OK", message=f"{history.candleCount} valid candles received")
    except HTTPException as exc:
        return PreflightResponse(ok=False, provider=UPSTREAM.name, symbolCount=len(symbols.items), sampleSymbol=sample, backendConfig=backend_check, authentication=auth_check, symbols=symbols_check, quote=quote_check, history=PreflightCheck(ok=False, code="HISTORY_ERROR", message=str(exc.detail)))

    return PreflightResponse(ok=True, provider=UPSTREAM.name, symbolCount=len(symbols.items), sampleSymbol=sample, backendConfig=backend_check, authentication=auth_check, symbols=symbols_check, quote=quote_check, history=history_check)


@app.get("/v1/bist/symbols", response_model=SymbolsResponse)
async def bist_symbols(authorization: str | None = Header(default=None)) -> SymbolsResponse:
    _require_client_auth(authorization)
    return await _symbols_impl()


@app.get("/v1/bist/quote/{symbol}", response_model=QuoteResponse)
async def bist_quote(symbol: str, authorization: str | None = Header(default=None)) -> QuoteResponse:
    _require_client_auth(authorization)
    return await _quote_impl(symbol)


@app.get("/v1/bist/history/{symbol}", response_model=HistoryResponse)
async def bist_history(symbol: str, range: str = Query(default="1y"), interval: str = Query(default="1d"), authorization: str | None = Header(default=None)) -> HistoryResponse:
    _require_client_auth(authorization)
    if range != "1y" or interval != "1d":
        raise HTTPException(status_code=400, detail={"code": "UNSUPPORTED_RANGE", "message": "Only range=1y&interval=1d is supported by the mobile contract"})
    return await _history_impl(symbol)


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
