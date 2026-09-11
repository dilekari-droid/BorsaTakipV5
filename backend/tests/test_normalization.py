import importlib
import os
from pathlib import Path
import sys
import time

import pytest
from fastapi import HTTPException


os.environ.setdefault("BORSA_SYMBOLS_URL", "https://provider.example/symbols")
os.environ.setdefault("BORSA_HISTORY_URL_TEMPLATE", "https://provider.example/history/{symbol}")

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
main = importlib.import_module("app.main")


def payload(symbol="TEST"):
    now = int(time.time() * 1000)
    return {
        "symbol": symbol,
        "realtime": True,
        "currentSessionIncluded": True,
        "delaySeconds": 0,
        "dataTimestamp": now,
        "candles": [
            {
                "timestamp": now - (220 - i) * 86_400_000,
                "open": 10.0,
                "high": 11.0,
                "low": 9.0,
                "close": 10.5,
                "volume": 1000.0,
            }
            for i in range(220)
        ],
    }


def test_valid_history_is_accepted():
    result = main._normalize_candles(payload(), "TEST")
    assert len(result.candles) == 220
    assert result.symbol == "TEST"


def test_duplicate_timestamp_does_not_satisfy_minimum():
    value = payload()
    value["candles"].append(dict(value["candles"][-1]))
    value["candles"].pop(0)
    with pytest.raises(HTTPException) as error:
        main._normalize_candles(value, "TEST")
    assert error.value.status_code == 422


def test_invalid_ohlc_geometry_is_rejected():
    value = payload()
    value["candles"][0]["high"] = 9.5
    with pytest.raises(HTTPException) as error:
        main._normalize_candles(value, "TEST")
    assert error.value.status_code == 422


def test_upstream_symbol_must_match_request():
    with pytest.raises(HTTPException) as error:
        main._normalize_candles(payload("OTHER"), "TEST")
    assert error.value.status_code == 409
