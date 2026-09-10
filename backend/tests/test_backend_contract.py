from __future__ import annotations

import importlib
import os
import time

import pytest
from fastapi import HTTPException


def load_main(**env):
    keys = [
        "BORSA_BACKEND_API_KEY",
        "BORSA_UPSTREAM_NAME",
        "BORSA_UPSTREAM_TOKEN",
        "BORSA_SYMBOLS_URL",
        "BORSA_QUOTE_URL_TEMPLATE",
        "BORSA_HISTORY_URL_TEMPLATE",
        "BORSA_VIOP_URL",
        "BORSA_MAX_REALTIME_AGE_MS",
        "BORSA_MAX_DECLARED_DELAY_SECONDS",
    ]
    old = {k: os.environ.get(k) for k in keys}
    try:
        for k in keys:
            os.environ.pop(k, None)
        for k, v in env.items():
            os.environ[k] = v
        import app.provider_adapter as provider_adapter
        import app.main as main
        importlib.reload(provider_adapter)
        return importlib.reload(main)
    finally:
        for k, v in old.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def candles(count: int, now: int):
    return [
        {
            "timestamp": now - (count - 1 - i) * 86_400_000,
            "open": 100.0 + i * 0.01,
            "high": 101.0 + i * 0.01,
            "low": 99.0 + i * 0.01,
            "close": 100.5 + i * 0.01,
            "volume": 1_000_000 + i,
        }
        for i in range(count)
    ]


def test_health_is_not_ready_when_endpoints_missing():
    main = load_main()
    assert main.UPSTREAM.configuration_ready() is False


def test_http_upstream_is_rejected():
    main = load_main(
        BORSA_SYMBOLS_URL="http://invalid.local/symbols",
        BORSA_QUOTE_URL_TEMPLATE="https://valid.invalid/quote/{symbol}",
        BORSA_HISTORY_URL_TEMPLATE="https://valid.invalid/history/{symbol}",
    )
    assert main.UPSTREAM.configuration_ready() is False


def test_realtime_false_is_rejected():
    main = load_main()
    payload = {"realtime": False, "currentSessionIncluded": True, "delaySeconds": 0, "dataTimestamp": int(time.time() * 1000)}
    with pytest.raises(HTTPException) as exc:
        main._require_realtime_metadata(payload)
    assert exc.value.status_code == 409
    assert exc.value.detail["code"] == "NOT_REALTIME"


def test_current_session_missing_is_rejected():
    main = load_main()
    payload = {"realtime": True, "currentSessionIncluded": False, "delaySeconds": 0, "dataTimestamp": int(time.time() * 1000)}
    with pytest.raises(HTTPException) as exc:
        main._require_realtime_metadata(payload)
    assert exc.value.detail["code"] == "SESSION_MISSING"


def test_delay_over_five_seconds_is_rejected():
    main = load_main(BORSA_MAX_DECLARED_DELAY_SECONDS="5")
    payload = {"realtime": True, "currentSessionIncluded": True, "delaySeconds": 6, "dataTimestamp": int(time.time() * 1000)}
    with pytest.raises(HTTPException) as exc:
        main._require_realtime_metadata(payload)
    assert exc.value.detail["code"] == "DELAY_TOO_HIGH"


def test_stale_timestamp_is_rejected():
    main = load_main(BORSA_MAX_REALTIME_AGE_MS="60000")
    payload = {"realtime": True, "currentSessionIncluded": True, "delaySeconds": 0, "dataTimestamp": int(time.time() * 1000) - 120_000}
    with pytest.raises(HTTPException) as exc:
        main._require_realtime_metadata(payload)
    assert exc.value.detail["code"] == "STALE_DATA"


def test_future_timestamp_is_rejected():
    main = load_main()
    payload = {"realtime": True, "currentSessionIncluded": True, "delaySeconds": 0, "dataTimestamp": int(time.time() * 1000) + 30_000}
    with pytest.raises(HTTPException) as exc:
        main._require_realtime_metadata(payload)
    assert exc.value.detail["code"] == "FUTURE_TIMESTAMP"


def test_less_than_220_candles_is_422():
    main = load_main()
    now = int(time.time() * 1000)
    payload = {
        "symbol": "THYAO",
        "dataTimestamp": now,
        "candles": candles(219, now),
    }
    with pytest.raises(HTTPException) as exc:
        main._normalize_candles(payload, "THYAO")
    assert exc.value.status_code == 422
    assert exc.value.detail["code"] == "INSUFFICIENT_HISTORY"


def test_220_valid_candles_pass_as_historical_contract():
    main = load_main()
    now = int(time.time() * 1000)
    payload = {
        "symbol": "THYAO",
        "dataTimestamp": now,
        "candles": candles(220, now),
    }
    result = main._normalize_candles(payload, "THYAO")
    assert result.symbol == "THYAO"
    assert len(result.candles) == 220
    assert result.candleCount == 220
    assert result.dataMode == "HISTORICAL"
    assert not hasattr(result, "realtime")
