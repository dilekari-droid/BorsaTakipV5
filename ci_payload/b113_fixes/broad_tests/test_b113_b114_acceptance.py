from __future__ import annotations

import math
import time
from datetime import datetime, timedelta
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.realtime_engine import (
    ISTANBUL,
    MarketTick,
    MinuteCandle,
    RealtimeMarketEngine,
    ScannerOpportunity,
    _aggregate_minutes,
    _atr,
    _data_quality_score,
    _risk_score,
    _rsi,
    _session_vwap,
)
from test_tradingview_webhook import load_main


AUTH = {"Authorization": "Bearer client-api-key-123456"}
INGEST = {"X-Live-Ingest-Key": "0123456789abcdef0123456789abcdef"}


def _aligned_rows(count: int, *, start_price: float = 100.0, down: bool = False) -> list[MinuteCandle]:
    # Use yesterday so the whole fixture is closed and cannot accidentally point into the future.
    day = datetime.now(ISTANBUL).date() - timedelta(days=1)
    start = datetime.combine(day, datetime.min.time(), tzinfo=ISTANBUL).replace(hour=9, minute=40)
    rows: list[MinuteCandle] = []
    price = start_price
    for i in range(count):
        delta = (-0.08 if down else 0.08) + (0.02 if i % 4 == 0 else -0.005)
        o = price
        c = price + delta
        h = max(o, c) + 0.04
        l = min(o, c) - 0.04
        rows.append(MinuteCandle(int((start + timedelta(minutes=i)).timestamp() * 1000), o, h, l, c, 1000 + i * 3, True))
        price = c
    return rows


def _fresh_tick(symbol: str, price: float, *, full_quote: bool = True, cumulative: float = 1_000_000) -> MarketTick:
    now = int(time.time() * 1000)
    return MarketTick(
        symbol=symbol,
        price=price,
        bid=price - 0.02 if full_quote else None,
        ask=price + 0.02 if full_quote else None,
        cumulative_volume=cumulative,
        previous_close=price - 1.0 if full_quote else None,
        exchange_timestamp=now,
        received_at=now,
        source="TEST",
        provider_id="TEST",
    )


def _main_client():
    main = load_main(
        BORSA_BACKEND_API_KEY="client-api-key-123456",
        BORSA_LIVE_INGEST_SECRET="0123456789abcdef0123456789abcdef",
        BORSA_UPSTREAM_NAME="licensed-test-provider",
        BORSA_SYMBOLS_URL="https://valid.invalid/symbols",
        BORSA_HISTORY_URL_TEMPLATE="https://valid.invalid/history/{symbol}",
        BORSA_QUOTE_URL_TEMPLATE="",
    )
    return main, TestClient(main.app)


def test_scan_cadence_is_independent_from_analysis_timeframe_rest_contract():
    _, client = _main_client()
    r = client.get(
        "/v1/scanner/opportunities?limit=5&analysisTimeframeMinutes=15&scanCadenceMinutes=2&scanMode=PERIODIC",
        headers=AUTH,
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["analysisTimeframeMinutes"] == 15
    assert body["scanCadenceMinutes"] == 2
    assert body["scanMode"] == "PERIODIC"


def test_websocket_propagates_same_timeframe_cadence_and_mode_contract():
    _, client = _main_client()
    with client.websocket_connect("/v1/scanner/live", headers=AUTH) as ws:
        ws.send_json({
            "action": "subscribe", "limit": 5, "minScore": 0, "actionableOnly": False,
            "minIntervalMs": 250, "analysisTimeframeMinutes": 15,
            "scanCadenceMinutes": 3, "scanMode": "LIVE",
        })
        subscribed = ws.receive_json()
        snapshot = ws.receive_json()
        for payload in (subscribed, snapshot):
            assert payload["analysisTimeframeMinutes"] == 15
            assert payload["scanCadenceMinutes"] == 3
            assert payload["scanMode"] == "LIVE"
        assert snapshot["type"] == "scanner_snapshot"


def test_rest_and_websocket_snapshots_use_same_analysis_contract():
    _, client = _main_client()
    rest = client.get(
        "/v1/scanner/opportunities?limit=5&analysisTimeframeMinutes=5&scanCadenceMinutes=4&scanMode=LIVE",
        headers=AUTH,
    ).json()
    with client.websocket_connect("/v1/scanner/live", headers=AUTH) as ws:
        ws.send_json({
            "action":"subscribe", "limit":5, "minScore":0, "actionableOnly":False,
            "minIntervalMs":250, "analysisTimeframeMinutes":5, "scanCadenceMinutes":4, "scanMode":"LIVE",
        })
        ws.receive_json()
        live = ws.receive_json()
    for key in ("analysisTimeframeMinutes", "scanCadenceMinutes", "scanMode", "universeVerified", "universeSymbols"):
        assert live[key] == rest[key]


def test_readiness_is_independent_per_timeframe():
    engine = RealtimeMarketEngine(max_bars=800, min_ready_bars=35)
    rows = _aligned_rows(80)
    engine.seed("THYAO", rows)
    engine.ingest(_fresh_tick("THYAO", rows[-1].close + 0.2))
    status = engine.status(1)
    assert status["readinessByTimeframe"]["1"] == 1
    assert status["readinessByTimeframe"]["15"] == 0


def test_incomplete_aggregate_bucket_is_never_confirmed():
    rows = _aligned_rows(30)
    # First 15m bucket is complete; second bucket contains one open 1m candle and must be omitted.
    rows[-1].closed = False
    agg = _aggregate_minutes(rows, 15)
    assert len(agg) == 1
    assert agg[0].closed is True
    assert agg[0].timestamp == rows[0].timestamp


def test_risk_is_independent_from_signal_score():
    risk = _risk_score(price=100.0, bid=99.99, ask=100.01, atr=1.2, relative_volume=1.4, age_ms=500, max_age_ms=60_000)
    signal_score = 82
    assert 0 <= risk <= 100
    assert risk != 100 - signal_score


def test_data_quality_is_evidence_based_not_hardcoded_100():
    sparse = _data_quality_score(
        age_ms=45_000, max_age_ms=60_000, bid=None, ask=None, previous_close=None,
        relative_volume=None, rsi=None, macd=None, atr=None, vwap=None, ema20=None, ema50=None,
        closed_bucket=True,
    )
    complete = _data_quality_score(
        age_ms=0, max_age_ms=60_000, bid=99.9, ask=100.1, previous_close=99.0,
        relative_volume=1.2, rsi=55.0, macd=0.5, atr=1.0, vwap=99.8, ema20=100.0, ema50=98.0,
        closed_bucket=True,
    )
    assert sparse < complete
    assert sparse < 100
    assert complete == 100


def test_ema20_50_are_not_macd_ema12_26_aliases():
    engine = RealtimeMarketEngine(max_bars=400, min_ready_bars=35)
    rows = _aligned_rows(220)
    engine.seed("THYAO", rows)
    engine.ingest(_fresh_tick("THYAO", rows[-1].close + 0.3))
    x = engine.opportunities(limit=1, interval_minutes=1)[0]
    assert x.ema20 is not None and x.ema50 is not None
    assert x.ema_fast is not None and x.ema_slow is not None
    assert not math.isclose(x.ema20, x.ema_fast, rel_tol=0, abs_tol=1e-9)
    assert not math.isclose(x.ema50, x.ema_slow, rel_tol=0, abs_tol=1e-9)


def test_rsi_wilder_golden_value():
    # Canonical Wilder RSI example: first 14-period RSI is approximately 70.4641.
    closes = [44.34,44.09,44.15,43.61,44.33,44.83,45.10,45.42,45.84,46.08,45.89,46.03,45.61,46.28,46.28]
    value = _rsi(closes, 14)
    assert value is not None
    assert value == pytest.approx(70.464135, abs=1e-5)


def test_atr_wilder_golden_value():
    highs = [10,11,12,13,14,15]
    lows = [8,9,10,11,12,13]
    closes = [9,10,11,12,13,14]
    # TR is exactly 2 for each bar, so Wilder ATR(3) must stay exactly 2.
    assert _atr(highs, lows, closes, 3) == pytest.approx(2.0, abs=1e-12)


def test_session_vwap_resets_on_new_istanbul_date():
    yesterday = _aligned_rows(2, start_price=10.0)
    today_start = datetime.now(ISTANBUL).replace(hour=9, minute=40, second=0, microsecond=0)
    today = [
        MinuteCandle(int(today_start.timestamp()*1000), 20, 21, 19, 20, 100, True),
        MinuteCandle(int((today_start+timedelta(minutes=1)).timestamp()*1000), 22, 23, 21, 22, 300, True),
    ]
    value = _session_vwap(yesterday + today)
    expected = (((21+19+20)/3)*100 + ((23+21+22)/3)*300) / 400
    assert value == pytest.approx(expected, abs=1e-12)


def test_out_of_order_tick_does_not_mutate_any_engine_state():
    engine = RealtimeMarketEngine(min_ready_bars=35)
    rows = _aligned_rows(50)
    engine.seed("THYAO", rows)
    now = int(time.time()*1000)
    current = _fresh_tick("THYAO", rows[-1].close + 0.2, cumulative=1_000_000)
    current.exchange_timestamp = now
    current.received_at = now
    engine.ingest(current)
    version = engine.version
    before = [(c.timestamp,c.open,c.high,c.low,c.close,c.volume,c.closed) for c in engine.candles("THYAO")]
    old = _fresh_tick("THYAO", current.price + 50, cumulative=2_000_000)
    old.exchange_timestamp = now - 1_000
    old.received_at = now + 1
    engine.ingest(old)
    after = [(c.timestamp,c.open,c.high,c.low,c.close,c.volume,c.closed) for c in engine.candles("THYAO")]
    assert engine.version == version
    assert after == before
    assert engine._ticks["THYAO"].price == current.price
    assert engine._last_cumulative_volume["THYAO"] == 1_000_000


def test_unknown_universe_is_not_reported_as_full_bist():
    engine = RealtimeMarketEngine(min_ready_bars=35)
    rows = _aligned_rows(50)
    engine.seed("THYAO", rows)
    engine.ingest(_fresh_tick("THYAO", rows[-1].close + 0.1))
    status = engine.status(1)
    assert status["trackedSymbols"] == 1
    assert status["universeVerified"] is False
    assert status["universeSymbols"] == 0
    assert status["readyCoveragePct"] is None


def test_malformed_or_future_seed_fails_closed():
    engine = RealtimeMarketEngine()
    row = _aligned_rows(1)[0]
    with pytest.raises(ValueError):
        engine.seed("THYAO", [MinuteCandle(row.timestamp, row.open, row.high, row.low, float("nan"), row.volume, True)])
    future = int(time.time()*1000) + 60_000
    with pytest.raises(ValueError):
        engine.seed("THYAO", [MinuteCandle(future, 10, 11, 9, 10.5, 100, True)])


def test_filter_is_applied_before_transport_limit(monkeypatch):
    main, _ = _main_client()

    def opp(symbol: str, direction: str, score: int) -> ScannerOpportunity:
        return ScannerOpportunity(
            symbol=symbol, price=100, bid=99.9, ask=100.1, daily_change_pct=None, change_1m_pct=0.1,
            relative_volume=1.0, rsi=50, ema_fast=99, ema_slow=98, ema20=99.5, ema50=98.5, ema200=None,
            macd=0.2, macd_signal=0.1, macd_histogram=0.1, atr=1.0, vwap=99.0,
            breakout=False, breakout_direction="NONE", momentum="NEUTRAL", trend="UP", stock_trend="UP",
            market_regime="UNKNOWN", direction=direction, score=score, long_score=score if direction=="LONG" else 0,
            short_score=score if direction=="SHORT" else 0, risk_score=25, data_quality_score=90,
            confirmed_closed_bar=True, data_age_ms=10, exchange_timestamp=int(time.time()*1000),
            received_at=int(time.time()*1000), source="TEST", provider_id="TEST", candle_count=80,
            ready=True, interval_minutes=1, score_breakdown=[],
        )

    rows = [opp("AAA", "WATCH", 99), opp("BBB", "LONG", 80), opp("CCC", "LONG", 70)]
    monkeypatch.setattr(main.REALTIME_ENGINE, "opportunities", lambda **kwargs: rows)
    monkeypatch.setattr(main.REALTIME_ENGINE, "status", lambda interval: {
        "trackedSymbols":3,"freshSymbols":3,"readySymbols":3,"universeVerified":False,"universeSymbols":0,
        "freshCoveragePct":None,"readyCoveragePct":None,"minReadyBars":60,"readinessByTimeframe":{},
    })
    payload = main._scanner_snapshot_payload(limit=1, min_score=0, direction="LONG", analysis_timeframe_minutes=1)
    assert [x["symbol"] for x in payload["items"]] == ["BBB"]
