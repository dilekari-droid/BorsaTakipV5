from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
README = (BACKEND_ROOT / "README.md").read_text(encoding="utf-8")
ENV_EXAMPLE = (BACKEND_ROOT / ".env.example").read_text(encoding="utf-8")
MAIN = (BACKEND_ROOT / "app" / "main.py").read_text(encoding="utf-8")
PROVIDER = (BACKEND_ROOT / "app" / "provider_adapter.py").read_text(encoding="utf-8")

ENDPOINTS = (
    "/v1/health",
    "/v1/preflight",
    "/v1/bist/symbols",
    "/v1/bist/quote/{symbol}",
    "/v1/bist/history/{symbol}",
    "/v1/viop/contracts",
    "/v1/viop/quote/{symbol}",
    "/v1/viop/history/{symbol}",
)

DEPLOYMENT_KEYS = (
    "BORSA_BACKEND_API_KEY",
    "BORSA_UPSTREAM_NAME",
    "BORSA_UPSTREAM_TOKEN",
    "BORSA_SYMBOLS_URL",
    "BORSA_QUOTE_URL_TEMPLATE",
    "BORSA_HISTORY_URL_TEMPLATE",
    "BORSA_VIOP_URL",
    "BORSA_VIOP_QUOTE_URL_TEMPLATE",
    "BORSA_VIOP_HISTORY_URL_TEMPLATE",
)


def test_readme_lists_every_implemented_android_endpoint():
    for endpoint in ENDPOINTS:
        assert endpoint in MAIN, f"route missing from backend main.py: {endpoint}"
        assert endpoint in README, f"README missing Android contract route: {endpoint}"


def test_env_example_lists_every_provider_configuration_key():
    for key in DEPLOYMENT_KEYS:
        assert key in ENV_EXAMPLE, f".env.example missing: {key}"


def test_provider_adapter_reads_every_market_endpoint_from_environment():
    for key in (
        "BORSA_SYMBOLS_URL",
        "BORSA_QUOTE_URL_TEMPLATE",
        "BORSA_HISTORY_URL_TEMPLATE",
        "BORSA_VIOP_URL",
        "BORSA_VIOP_QUOTE_URL_TEMPLATE",
        "BORSA_VIOP_HISTORY_URL_TEMPLATE",
    ):
        assert key in PROVIDER, f"provider adapter missing env contract: {key}"


def test_health_and_viop_readiness_are_documented_separately():
    assert "configurationReady" in README
    assert "authenticationConfigured" in README
    assert "viopConfigured" in README
    assert "health.ok=true" in README
    assert "tek başına" in README
