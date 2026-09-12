from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

insert = '''\n\nclass ViopHealth(BaseModel):\n    ok: bool\n    provider: str\n    connectionStatus: str\n    configured: bool\n    lastUpdate: int | None = None\n    dataAgeMs: int | None = None\n    latencyMs: int | None = None\n    errorCode: str | None = None\n    message: str\n\n'''
needle = 'class SymbolsResponse(BaseModel):\n'
if 'class ViopHealth(BaseModel):' not in s:
    s = s.replace(needle, insert + needle)

route = '''\n\n@app.get("/v1/viop/health", response_model=ViopHealth)\nasync def viop_health(authorization: str | None = Header(default=None)) -> ViopHealth:\n    _require_client_auth(authorization)\n    configured = UPSTREAM.viop_configuration_ready()\n    if not configured:\n        return ViopHealth(ok=False, provider=UPSTREAM.name, connectionStatus="NOT_CONFIGURED", configured=False, errorCode="VIOP_PROVIDER_NOT_CONFIGURED", message="Licensed VIOP upstream is not fully configured")\n    started = time.perf_counter()\n    try:\n        if not UPSTREAM.viop_url:\n            raise HTTPException(status_code=503, detail={"code":"NOT_CONFIGURED","message":"VIOP upstream is not configured"})\n        payload = await UPSTREAM.get_json(UPSTREAM.viop_url)\n        rows = payload.get("items") if isinstance(payload,dict) else payload\n        if not isinstance(rows,list) or not rows:\n            return ViopHealth(ok=False, provider=UPSTREAM.name, connectionStatus="ERROR", configured=True, latencyMs=int((time.perf_counter()-started)*1000), errorCode="EMPTY_CONTRACTS", message="VIOP upstream returned no contracts")\n        valid = [_normalize_viop_row(r) for r in rows if isinstance(r,dict)]\n        valid = [x for x in valid if x is not None]\n        if not valid:\n            return ViopHealth(ok=False, provider=UPSTREAM.name, connectionStatus="ERROR", configured=True, latencyMs=int((time.perf_counter()-started)*1000), errorCode="INVALID_DATA", message="No VIOP contract passed validation")\n        last = max(x.dataTimestamp for x in valid)\n        now = int(time.time()*1000)\n        return ViopHealth(ok=True, provider=UPSTREAM.name, connectionStatus="ONLINE", configured=True, lastUpdate=last, dataAgeMs=max(0, now-last), latencyMs=int((time.perf_counter()-started)*1000), message=f"{len(valid)} validated VIOP contracts available")\n    except ProviderError as exc:\n        return ViopHealth(ok=False, provider=UPSTREAM.name, connectionStatus="ERROR", configured=True, latencyMs=int((time.perf_counter()-started)*1000), errorCode=str(exc.code.value), message=str(exc))\n\n'''
needle2 = '@app.get("/v1/viop/contracts", response_model=ViopResponse)\n'
if '/v1/viop/health' not in s:
    s = s.replace(needle2, route + needle2)

p.write_text(s)
