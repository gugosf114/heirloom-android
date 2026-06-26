"""
Async Replicate client (ported from worker/src/replicate.ts).

Same lifecycle: create a prediction with `Prefer: wait=30`, then poll. Unlike
the Cloudflare Worker there is NO 50-subrequest cap here, so we can poll
faster (2s) and never starve a later stage. Version strings containing '/'
are treated as deployment endpoints (e.g. a custom model); bare SHAs hit the
public predictions endpoint.
"""

import asyncio
import json
from typing import Any, Dict

import httpx

REPLICATE_BASE = "https://api.replicate.com/v1"
POLL_INTERVAL_S = 2.0
POLL_TIMEOUT_S = 480.0
_TERMINAL = {"succeeded", "failed", "canceled"}


async def run_replicate(token: str, version: str, model_input: Dict[str, Any]) -> Any:
    async with httpx.AsyncClient(timeout=60.0) as client:
        prediction = await _create_with_retry(client, token, version, model_input)
        loop_started = asyncio.get_event_loop().time()
        while prediction.get("status") not in _TERMINAL:
            if asyncio.get_event_loop().time() - loop_started > POLL_TIMEOUT_S:
                raise RuntimeError(f"Replicate poll timeout for {prediction.get('id')}")
            await asyncio.sleep(POLL_INTERVAL_S)
            r = await client.get(
                f"{REPLICATE_BASE}/predictions/{prediction['id']}",
                headers={"Authorization": f"Token {token}"},
            )
            r.raise_for_status()
            prediction = r.json()

        if prediction.get("status") != "succeeded":
            raise RuntimeError(
                f"Replicate {prediction.get('status')}: {prediction.get('error') or 'unknown'}"
            )
        return prediction.get("output")


async def _create_with_retry(client, token, version, model_input, max_retries=5):
    is_deployment = "/" in version
    url = (
        f"{REPLICATE_BASE}/deployments/{version}/predictions"
        if is_deployment
        else f"{REPLICATE_BASE}/predictions"
    )
    body = {"input": model_input} if is_deployment else {"version": version, "input": model_input}

    for attempt in range(max_retries + 1):
        resp = await client.post(
            url,
            headers={
                "Authorization": f"Token {token}",
                "Content-Type": "application/json",
                "Prefer": "wait=30",
            },
            content=json.dumps(body),
        )
        if resp.status_code < 300:
            return resp.json()
        text = resp.text
        if resp.status_code != 429 or attempt == max_retries:
            raise RuntimeError(f"Replicate create failed ({resp.status_code}): {text}")
        wait_s = 10
        header = resp.headers.get("retry-after")
        if header and header.isdigit():
            wait_s = int(header)
        else:
            try:
                wait_s = int(json.loads(text).get("retry_after", 10))
            except Exception:
                pass
        await asyncio.sleep(wait_s + 1)
    raise RuntimeError("Replicate create: exhausted retry budget")


def as_url(output: Any) -> str:
    """Normalize a Replicate output to a single URL."""
    if isinstance(output, str):
        return output
    if isinstance(output, list) and output and isinstance(output[0], str):
        return output[0]
    if isinstance(output, dict):
        for key in ("image", "output"):
            if isinstance(output.get(key), str):
                return output[key]
    raise RuntimeError(f"Unexpected Replicate output shape: {json.dumps(output)[:200]}")
