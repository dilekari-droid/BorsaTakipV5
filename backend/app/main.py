from __future__ import annotations

import os
import time
from typing import Any
from urllib.parse import quote

import httpx
from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel

app = FastAPI(title="BorsaTakip Backend", version="1.1.0")

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
    name: str | None = None
    expiry: str | None = None
    last: float | None = None
    changePct: float | None = None
    volume: float | None = None
    openInterest: float | None = None
    source: str
    dataTimestamp: int


class ViopResponse(BaseModel):
    items: list[ViopContract]
    source: str
    dataTimestamp: int


def _require_client_auth(authorization: str | None) -> None:
    if not API_KEY:
        return
    expected = f"Bearer {API_KEY}"
    if authorization != expected:
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
        if candle.timestamp <= 0 or candle.high < candle.low or candle.volume < 0:
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


@app.get("/v1/health", response_model=Health)
async def health(authorization: str | None = Header(default=None)) -> Health:
    _require_client_auth(authorization)
    configured = all([
        SYMBOLS_URL.startswith("https://"),
        HISTORY_URL_TEMPLATE.startswith("https://"),
    ])
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
    url = HISTORY_URL_TEMPLATE.replace("{symbol}", quote(normalized, safe=""))
    payload = await _get_json(url)
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
    now = int(time.time() * 1000)
    items: list[ViopContract] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        symbol = str(row.get("symbol") or "").strip().upper()
        if not symbol:
            continue
        items.append(ViopContract(
            symbol=symbol,
            name=str(row.get("name")).strip() if row.get("name") else None,
            expiry=str(row.get("expiry")).strip() if row.get("expiry") else None,
            last=float(row["last"]) if row.get("last") is not None else None,
            changePct=float(row["changePct"]) if row.get("changePct") is not None else None,
            volume=float(row["volume"]) if row.get("volume") is not None else None,
            openInterest=float(row["openInterest"]) if row.get("openInterest") is not None else None,
            source=UPSTREAM_NAME,
            dataTimestamp=int(row.get("dataTimestamp") or now),
        ))
    if not items:
        raise HTTPException(status_code=502, detail="Upstream returned no valid VIOP contracts")
    return ViopResponse(items=items, source=UPSTREAM_NAME, dataTimestamp=now)
