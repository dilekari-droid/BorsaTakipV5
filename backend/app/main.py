from __future__ import annotations

import math
import os
import re
import time
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote

import httpx
from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel

app = FastAPI(title="BorsaTakip Backend", version="1.2.0")

API_KEY = os.getenv("BORSA_BACKEND_API_KEY", "").strip()
UPSTREAM_TOKEN = os.getenv("BORSA_UPSTREAM_TOKEN", "").strip()
SYMBOLS_URL = os.getenv("BORSA_SYMBOLS_URL", "").strip()
HISTORY_URL_TEMPLATE = os.getenv("BORSA_HISTORY_URL_TEMPLATE", "").strip()
VIOP_URL = os.getenv("BORSA_VIOP_URL", "").strip()
UPSTREAM_NAME = os.getenv("BORSA_UPSTREAM_NAME", "licensed-upstream").strip() or "licensed-upstream"
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


def _require_client_auth(authorization: str | None) -> None:
    if not API_KEY:
        return
    if authorization != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail="Unauthorized")


def _upstream_headers() -> dict[str, str]:
    headers = {"Accept": "application/json"}
    if UPSTREAM_TOKEN:
        headers["Authorization"] = f"Bearer {UPSTREAM_TOKEN}"
    return headers


async def _get_json(url: str) -> Any:
    if not url.startswith("https://"):
        raise HTTPException(status_code=503, detail="Production upstream is not configured with HTTPS")
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=8.0)) as client:
            response = await client.get(url, headers=_upstream_headers())
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Upstream connection failed: {exc.__class__.__name__}") from exc
    if response.status_code < 200 or response.status_code >= 300:
        raise HTTPException(status_code=502, detail=f"Upstream HTTP {response.status_code}")
    try:
        return response.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="Upstream returned invalid JSON") from exc


def _normalize_symbols(payload: Any) -> list[str]:
    raw = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(raw, list):
        raise HTTPException(status_code=502, detail="Upstream symbol response must contain an items array")
    out: list[str] = []
    seen: set[str] = set()
    for value in raw:
        symbol = str(value).strip().upper()
        if 3 <= len(symbol) <= 12 and symbol.replace("_", "").isalnum() and symbol not in seen:
            seen.add(symbol)
            out.append(symbol)
    if not out:
        raise HTTPException(status_code=502, detail="Upstream returned no valid BIST symbols")
    return out


def _require_realtime_metadata(payload: dict[str, Any]) -> tuple[int, int]:
    if payload.get("realtime") is not True:
        raise HTTPException(status_code=409, detail="Upstream did not certify data as real-time")
    if payload.get("currentSessionIncluded") is not True:
        raise HTTPException(status_code=409, detail="Current trading session is not included in OHLCV")
    try:
        delay_seconds = int(payload["delaySeconds"])
        data_ts = int(payload["dataTimestamp"])
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=409, detail="Real-time provenance metadata is incomplete") from exc
    if delay_seconds < 0 or delay_seconds > MAX_DECLARED_DELAY_SECONDS:
        raise HTTPException(status_code=409, detail=f"Declared provider delay is {delay_seconds}s; strict limit is {MAX_DECLARED_DELAY_SECONDS}s")
    now = int(time.time() * 1000)
    age = now - data_ts
    if data_ts <= 0 or age > MAX_REALTIME_AGE_MS:
        raise HTTPException(status_code=409, detail=f"Market data is stale; age={max(age, 0)}ms strict_limit={MAX_REALTIME_AGE_MS}ms")
    if age < -15_000:
        raise HTTPException(status_code=409, detail="Market timestamp is ahead of server clock")
    return delay_seconds, data_ts


def _normalize_candles(payload: Any, requested_symbol: str) -> HistoryResponse:
    if not isinstance(payload, dict):
        raise HTTPException(status_code=502, detail="Upstream history response must be an object")
    delay_seconds, data_ts = _require_realtime_metadata(payload)
    rows = payload.get("candles")
    if not isinstance(rows, list):
        raise HTTPException(status_code=502, detail="Upstream history response must contain candles")
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
        raise HTTPException(status_code=422, detail=f"Insufficient OHLCV history: {len(candles)} candles; minimum 220")
    symbol = str(payload.get("symbol") or requested_symbol).strip().upper()
    name = payload.get("name")
    return HistoryResponse(
        symbol=symbol,
        name=str(name).strip() if name else None,
        candles=candles,
        source=UPSTREAM_NAME,
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

    open_interest_raw = row.get("openInterest")
    open_interest: int | None = None
    if open_interest_raw is not None:
        try:
            parsed_oi = int(open_interest_raw)
            if parsed_oi >= 0:
                open_interest = parsed_oi
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
        source=str(row.get("source") or UPSTREAM_NAME).strip() or UPSTREAM_NAME,
        dataTimestamp=data_ts,
        realtime=True,
        delaySeconds=delay_seconds,
        currentSessionIncluded=True,
    )


@app.get("/v1/health", response_model=Health)
async def health(authorization: str | None = Header(default=None)) -> Health:
    _require_client_auth(authorization)
    configured = all([SYMBOLS_URL.startswith("https://"), HISTORY_URL_TEMPLATE.startswith("https://")])
    return Health(
        ok=configured,
        provider=UPSTREAM_NAME if configured else "unconfigured",
        message="Strict real-time production upstream configured" if configured else "Set licensed HTTPS BIST upstream endpoints",
        timestamp=int(time.time() * 1000),
        maxRealtimeAgeMs=MAX_REALTIME_AGE_MS,
        maxDeclaredDelaySeconds=MAX_DECLARED_DELAY_SECONDS,
    )


@app.get("/v1/bist/symbols", response_model=SymbolsResponse)
async def bist_symbols(authorization: str | None = Header(default=None)) -> SymbolsResponse:
    _require_client_auth(authorization)
    payload = await _get_json(SYMBOLS_URL)
    items = _normalize_symbols(payload)
    # This timestamp is response-generation metadata only; it is not reused as a market timestamp.
    return SymbolsResponse(items=items, source=UPSTREAM_NAME, dataTimestamp=int(time.time() * 1000))


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
        raise HTTPException(status_code=400, detail="Invalid symbol")
    if range != "1y" or interval != "1d":
        raise HTTPException(status_code=400, detail="Only range=1y&interval=1d is supported by the mobile contract")
    if "{symbol}" not in HISTORY_URL_TEMPLATE:
        raise HTTPException(status_code=503, detail="BORSA_HISTORY_URL_TEMPLATE must contain {symbol}")
    payload = await _get_json(HISTORY_URL_TEMPLATE.replace("{symbol}", quote(normalized, safe="")))
    return _normalize_candles(payload, normalized)


@app.get("/v1/viop/contracts", response_model=ViopResponse)
async def viop_contracts(authorization: str | None = Header(default=None)) -> ViopResponse:
    _require_client_auth(authorization)
    if not VIOP_URL:
        raise HTTPException(status_code=503, detail="VIOP upstream is not configured")
    payload = await _get_json(VIOP_URL)
    rows = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(rows, list):
        raise HTTPException(status_code=502, detail="Upstream VIOP response must contain an items array")
    items = [_normalize_viop_row(row) for row in rows if isinstance(row, dict)]
    valid_items = [item for item in items if item is not None]
    if not valid_items:
        raise HTTPException(status_code=422, detail="No VIOP contract passed expiry, parameter and real-time provenance validation")
    latest_ts = max(item.dataTimestamp for item in valid_items)
    return ViopResponse(items=valid_items, source=UPSTREAM_NAME, dataTimestamp=latest_ts)
