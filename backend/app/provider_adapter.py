from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass
from enum import Enum
from typing import Any

import httpx


class ProviderErrorCode(str, Enum):
    NOT_CONFIGURED = "NOT_CONFIGURED"
    HTTPS_REQUIRED = "HTTPS_REQUIRED"
    AUTH_ERROR = "AUTH_ERROR"
    HTTP_ERROR = "HTTP_ERROR"
    NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    DNS_ERROR = "DNS_ERROR"
    TLS_ERROR = "TLS_ERROR"
    INVALID_JSON = "INVALID_JSON"
    EMPTY_DATA = "EMPTY_DATA"


@dataclass(slots=True)
class ProviderError(Exception):
    code: ProviderErrorCode
    message: str
    status_code: int = 502

    def __str__(self) -> str:
        return self.message


class LicensedUpstreamProvider:
    """Generic adapter for a licensed/authorized market-data upstream.

    No concrete provider endpoint or secret is embedded in source control.
    Production readiness requires separate BIST and VIOP HTTPS contracts.
    """

    def __init__(self) -> None:
        self.name = os.getenv("BORSA_UPSTREAM_NAME", "licensed-upstream").strip() or "licensed-upstream"
        self.token = os.getenv("BORSA_UPSTREAM_TOKEN", "").strip()
        self.symbols_url = os.getenv("BORSA_SYMBOLS_URL", "").strip()
        self.quote_url_template = os.getenv("BORSA_QUOTE_URL_TEMPLATE", "").strip()
        self.history_url_template = os.getenv("BORSA_HISTORY_URL_TEMPLATE", "").strip()
        self.viop_url = os.getenv("BORSA_VIOP_URL", "").strip()
        self.viop_quote_url_template = os.getenv("BORSA_VIOP_QUOTE_URL_TEMPLATE", "").strip()
        self.viop_history_url_template = os.getenv("BORSA_VIOP_HISTORY_URL_TEMPLATE", "").strip()
        self.max_attempts = max(1, min(int(os.getenv("BORSA_UPSTREAM_MAX_ATTEMPTS", "3")), 3))

    def configuration_ready(self) -> bool:
        return (
            self.symbols_url.startswith("https://")
            and self.quote_url_template.startswith("https://")
            and "{symbol}" in self.quote_url_template
            and self.history_url_template.startswith("https://")
            and "{symbol}" in self.history_url_template
        )

    def viop_configuration_ready(self) -> bool:
        return (
            self.viop_url.startswith("https://")
            and self.viop_quote_url_template.startswith("https://")
            and "{symbol}" in self.viop_quote_url_template
            and self.viop_history_url_template.startswith("https://")
            and "{symbol}" in self.viop_history_url_template
        )

    def _headers(self) -> dict[str, str]:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    async def get_json(self, url: str) -> Any:
        if not url:
            raise ProviderError(ProviderErrorCode.NOT_CONFIGURED, "Licensed upstream endpoint is not configured", 503)
        if not url.startswith("https://"):
            raise ProviderError(ProviderErrorCode.HTTPS_REQUIRED, "Licensed upstream must use HTTPS", 503)

        last_error: ProviderError | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                async with httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=8.0)) as client:
                    response = await client.get(url, headers=self._headers())
                if response.status_code in (401, 403):
                    raise ProviderError(ProviderErrorCode.AUTH_ERROR, f"Upstream authorization failed (HTTP {response.status_code})", 502)
                if response.status_code < 200 or response.status_code >= 300:
                    raise ProviderError(ProviderErrorCode.HTTP_ERROR, f"Upstream HTTP {response.status_code}", 502)
                try:
                    payload = response.json()
                except ValueError as exc:
                    raise ProviderError(ProviderErrorCode.INVALID_JSON, "Upstream returned invalid JSON", 502) from exc
                if payload is None or payload == [] or payload == {}:
                    raise ProviderError(ProviderErrorCode.EMPTY_DATA, "Upstream returned empty data", 502)
                return payload
            except ProviderError:
                raise
            except httpx.TimeoutException:
                last_error = ProviderError(ProviderErrorCode.NETWORK_TIMEOUT, "Upstream request timed out", 504)
            except httpx.ConnectError as exc:
                low = (exc.__class__.__name__ + ": " + str(exc)).lower()
                if "certificate" in low or "ssl" in low or "tls" in low:
                    last_error = ProviderError(ProviderErrorCode.TLS_ERROR, "Upstream TLS connection failed", 502)
                elif "name or service not known" in low or "nodename nor servname" in low or "dns" in low:
                    last_error = ProviderError(ProviderErrorCode.DNS_ERROR, "Upstream DNS resolution failed", 502)
                else:
                    last_error = ProviderError(ProviderErrorCode.HTTP_ERROR, "Upstream connection failed", 502)
            except httpx.HTTPError:
                last_error = ProviderError(ProviderErrorCode.HTTP_ERROR, "Upstream HTTP transport failed", 502)

            if attempt < self.max_attempts:
                await asyncio.sleep(0.25 * (2 ** (attempt - 1)))

        raise last_error or ProviderError(ProviderErrorCode.HTTP_ERROR, "Upstream request failed", 502)
