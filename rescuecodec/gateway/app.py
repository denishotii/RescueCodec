"""The HTTP side of the gateway: spec/api/openapi.yaml as a FastAPI app, plus the dashboard and offline API docs."""
from __future__ import annotations

import asyncio
import hmac
import json
import logging
import mimetypes
import os
import re
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Literal

import yaml
from fastapi import Body, FastAPI, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException as StarletteHTTPException

from rescuecodec.gateway import extract_api
from rescuecodec.gateway.hub import ConnectorHub
from rescuecodec.gateway.service import ApiError, GatewayService

log = logging.getLogger("rescuecodec.gateway.app")

PACKAGE_DIR = Path(__file__).resolve().parent
DOCS_DIR = PACKAGE_DIR / "docs"
DEFAULT_STATIC_DIR = PACKAGE_DIR / "static"
MESSAGE_TYPES = ("found", "track", "sector", "request", "hazard", "mayday", "ack", "text", "unknown")
TASK_STATES = ("open", "accepted", "declined", "done")
TASK_KEY = r"^[0-9a-f]{8}-[0-9]{1,5}$"
LOCAL_ORIGIN = r"^https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$"

mimetypes.add_type("text/javascript", ".mjs")
mimetypes.add_type("application/octet-stream", ".pmtiles")
mimetypes.add_type("font/woff2", ".woff2")

PLACEHOLDER = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>RescueCodec gateway</title>
<style>
 body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:24px 16px;background:#f6f7f8;color:#14181c}
 main{max-width:40rem;margin:0 auto} h1{font-size:1.5rem;margin:0 0 .5rem}
 code{background:#e4e8eb;padding:.1em .35em;border-radius:4px} a{color:#0b5cad}
 @media (prefers-color-scheme:dark){body{background:#14181c;color:#e8ecef}code{background:#2a3138}a{color:#7db7f5}}
</style></head><body><main>
<h1>RescueCodec gateway is running</h1>
<p>The dashboard is not installed yet. The API works.</p>
<ul>
 <li><a href="/docs">API documentation</a> (works offline)</li>
 <li><a href="/v2/health">Health</a></li>
 <li><a href="/v2/messages">Messages</a>, <a href="/v2/tasks">Tasks</a>, <a href="/v2/nodes">Nodes</a></li>
 <li><a href="/v2/export.geojson">GeoJSON export</a></li>
</ul>
<p>To add the dashboard, build it into <code>rescuecodec/gateway/static/</code> (see <code>dashboard/</code>).</p>
</main></body></html>"""

DOCS_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>RescueCodec gateway API</title>
<link rel="icon" type="image/png" href="/docs-assets/favicon-32x32.png">
<link rel="stylesheet" href="/docs-assets/swagger-ui.css"></head>
<body><div id="swagger-ui"></div>
<script src="/docs-assets/swagger-ui-bundle.js"></script>
<script>window.ui = SwaggerUIBundle({url: "/openapi.json", dom_id: "#swagger-ui", deepLinking: true});</script>
</body></html>"""


def openapi_path() -> Path:
    env = os.getenv("RESCUECODEC_OPENAPI")
    return Path(env) if env else PACKAGE_DIR.parents[1] / "spec" / "api" / "openapi.yaml"


def error_response(error: ApiError) -> JSONResponse:
    headers = {"Retry-After": str(error.retry_after_s)} if error.retry_after_s is not None else None
    return JSONResponse(error.body(), status_code=error.status, headers=headers)


def csv_filter(value: str | None, allowed: tuple[str, ...], name: str) -> list[str] | None:
    if not value:
        return None
    items = [part.strip() for part in value.split(",") if part.strip()]
    bad = [item for item in items if item not in allowed]
    if bad:
        raise ApiError(400, "bad_request", f"unknown {name} {bad[0]!r}, use {', '.join(allowed)}", name)
    return items


def channel_filter(value: str | None) -> list[str] | None:
    """?channel=A,B -> ["A", "B"] (channel names, compared without case)."""
    names = [part.strip() for part in (value or "").split(",") if part.strip()]
    return names or None


def bearer_token(authorization: str | None, query_token: str | None) -> str | None:
    """The token a client sent: `Authorization: Bearer ...`, or ?token=... (browsers cannot set headers on a WebSocket)."""
    if authorization and authorization[:7].lower() == "bearer ":
        return authorization[7:].strip()
    return query_token


def origin_allowed(origin: str | None, host: str | None, cors_origins: list[str], token_ok: bool) -> bool:
    """Browsers send an Origin on a WebSocket and CORS does not apply to it, so we check it ourselves.
    No Origin means a program, not a web page: allowed. A web page must come from localhost, from --cors-origin,
    or (with a valid token) from this gateway itself."""
    if not origin:
        return True
    if re.match(LOCAL_ORIGIN, origin) or origin in cors_origins:
        return True
    return token_ok and bool(host) and origin.split("://", 1)[-1] == host


def create_app(service: GatewayService, *, hub: ConnectorHub | None = None, static_dir: str | Path | None = None,
               cors_origins: list[str] | None = None, start: bool = True, token: str | None = None) -> FastAPI:
    static_root = Path(static_dir) if static_dir else DEFAULT_STATIC_DIR
    allowed_origins = list(cors_origins or [])

    def token_ok(supplied: str | None) -> bool:
        return not token or (supplied is not None and hmac.compare_digest(supplied.encode(), token.encode()))

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if start:
            service.start()
            if hub:
                hub.start()
        try:
            yield
        finally:
            if start:
                if hub:
                    hub.stop()
                service.stop()

    app = FastAPI(title="RescueCodec Gateway", version="2.0.0", docs_url=None, redoc_url=None, openapi_url=None,
                  lifespan=lifespan)
    app.state.service = service

    if token:
        @app.middleware("http")
        async def require_token(request: Request, call_next):
            if request.url.path.startswith("/v2/") and request.method != "OPTIONS":
                if not token_ok(bearer_token(request.headers.get("authorization"), request.query_params.get("token"))):
                    response = error_response(ApiError(401, "unauthorized", "this gateway needs its token: send 'Authorization: Bearer <token>'"))
                    response.headers["WWW-Authenticate"] = "Bearer"
                    return response
            return await call_next(request)

    app.add_middleware(CORSMiddleware, allow_origin_regex=LOCAL_ORIGIN, allow_origins=cors_origins or [],
                       allow_methods=["GET", "POST"], allow_headers=["*"])

    # ------------------------------------------------------------ errors in the shape of openapi.yaml
    @app.exception_handler(ApiError)
    async def api_error(_: Request, exc: ApiError):
        return error_response(exc)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError):
        first = exc.errors()[0] if exc.errors() else {}
        loc = [str(part) for part in first.get("loc", ()) if part not in ("body", "query", "path")]
        message = first.get("msg", "invalid request")
        if first.get("type") == "json_invalid":
            message = "the body is not valid JSON"
        return error_response(ApiError(400, "bad_request", message, ".".join(loc) or None))

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_: Request, exc: StarletteHTTPException):
        code = "not_found" if exc.status_code == 404 else "bad_request"
        return error_response(ApiError(exc.status_code, code, str(exc.detail) if exc.detail else "error"))

    # ------------------------------------------------------------ system
    @app.get("/v2/health")
    def health():
        return service.health()

    @app.get("/v2/stats")
    def stats():
        return service.stats()

    @app.get("/v2/nodes")
    def nodes():
        return {"items": service.list_nodes()}

    # ------------------------------------------------------------ text mode per chat
    def mode_body(body: dict[str, Any]) -> Any:
        unknown = set(body) - {"mode"}
        if unknown:
            raise ApiError(400, "bad_request", f"unknown key {sorted(unknown)[0]!r}", sorted(unknown)[0])
        if "mode" not in body:
            raise ApiError(400, "bad_request", "the body needs a mode", "mode")
        return body["mode"]

    @app.get("/v2/channels/{name}/mode")
    def get_channel_mode(name: str):
        return service.chat_mode(channel=name)

    @app.put("/v2/channels/{name}/mode")
    def put_channel_mode(name: str, body: dict[str, Any] = Body(...)):
        return service.set_chat_mode(mode_body(body), channel=name)

    @app.get("/v2/nodes/{node}/mode")
    def get_node_mode(node: str):
        return service.chat_mode(node=node)

    @app.put("/v2/nodes/{node}/mode")
    def put_node_mode(node: str, body: dict[str, Any] = Body(...)):
        return service.set_chat_mode(mode_body(body), node=node)

    # ------------------------------------------------------------ messages
    @app.post("/v2/messages", status_code=202)
    def send_message(body: dict[str, Any] = Body(...)):
        unknown = set(body) - {"message", "want_ack", "dry_run", "channel", "to"}
        if unknown:
            raise ApiError(400, "bad_request", f"unknown key {sorted(unknown)[0]!r}", sorted(unknown)[0])
        if "message" not in body:
            raise ApiError(400, "bad_request", "the body needs a message", "message")
        for flag in ("want_ack", "dry_run"):
            if flag in body and not isinstance(body[flag], bool):
                raise ApiError(400, "bad_request", f"{flag} must be true or false", flag)
        return service.send_message(body["message"], want_ack=body.get("want_ack", True), dry_run=body.get("dry_run", False),
                                    channel=body.get("channel"), to=body.get("to"))

    @app.get("/v2/messages")
    def list_messages(since_seq: int = Query(0, ge=0), type: str | None = None, direction: Literal["in", "out"] | None = None,
                      limit: int = Query(100, ge=1, le=1000), channel: str | None = None):
        return service.list_messages(since_seq, csv_filter(type, MESSAGE_TYPES, "type"), direction, limit,
                                     channel_filter(channel))

    @app.get("/v2/messages/{seq}")
    def get_message(seq: int):
        if seq < 1:
            raise ApiError(404, "not_found", f"no message {seq}")
        return service.get_message(seq)

    @app.post("/v2/text", status_code=202)
    def send_text(body: dict[str, Any] = Body(...)):
        unknown = set(body) - {"text", "priority", "also_plain", "channel", "to"}
        if unknown:
            raise ApiError(400, "bad_request", f"unknown key {sorted(unknown)[0]!r}", sorted(unknown)[0])
        if "text" not in body:
            raise ApiError(400, "bad_request", "the body needs a text", "text")
        also_plain = body.get("also_plain", False)
        if not isinstance(also_plain, bool):
            raise ApiError(400, "bad_request", "also_plain must be true or false", "also_plain")
        return service.send_text(body["text"], body.get("priority"), also_plain, body.get("channel"), body.get("to"))

    # ------------------------------------------------------------ tasks
    @app.get("/v2/tasks")
    def list_tasks(state: str | None = None, type: str | None = None, channel: str | None = None):
        states = csv_filter(state, TASK_STATES, "state")
        types = csv_filter(type, MESSAGE_TYPES, "type")
        return {"items": service.list_tasks(set(states) if states else None, set(types) if types else None,
                                            channel_filter(channel))}

    @app.get("/v2/tasks/{key}")
    def get_task(key: str):
        return service.get_task(key)

    @app.post("/v2/tasks/{key}/ack", status_code=202)
    def ack_task(key: str, body: dict[str, Any] = Body(...)):
        unknown = set(body) - {"status", "eta", "team"}
        if unknown:
            raise ApiError(400, "bad_request", f"unknown key {sorted(unknown)[0]!r}", sorted(unknown)[0])
        if "status" not in body:
            raise ApiError(400, "bad_request", "the body needs a status", "status")
        task, record = service.ack_task(key, body["status"], body.get("eta"), body.get("team"))
        return {"task": task, "record": record}

    # ------------------------------------------------------------ export and tools
    @app.get("/v2/export.geojson")
    def export_geojson(since_seq: int = Query(0, ge=0), type: str | None = None, channel: str | None = None):
        data = service.geojson(since_seq, csv_filter(type, MESSAGE_TYPES, "type"), channel_filter(channel))
        return Response(json.dumps(data, ensure_ascii=False), media_type="application/geo+json")

    @app.post("/v2/encode")
    def encode_message(body: dict[str, Any] = Body(...)):
        if "message" not in body:
            raise ApiError(400, "bad_request", "the body needs a message", "message")
        return service.encode_preview(body["message"])

    @app.post("/v2/extract")
    def extract_message(body: Any = Body(...)):
        try:
            return extract_api.preview(body, next_id=service.store.peek_message_id,
                                       text_as_meshtastic=service.config.text_as_meshtastic)
        except extract_api.ExtractError as exc:
            raise ApiError(400, "bad_request", exc.message, exc.field) from None

    @app.post("/v2/decode")
    def decode_message(body: dict[str, Any] = Body(...)):
        if "hex" not in body:
            raise ApiError(400, "bad_request", "the body needs hex", "hex")
        return service.decode_hex(body["hex"])

    # ------------------------------------------------------------ live events
    @app.get("/v2/stream")
    def stream_needs_upgrade():
        return error_response(ApiError(400, "bad_request", "this endpoint needs a WebSocket upgrade (ws://host/v2/stream)"))

    @app.websocket("/v2/stream")
    async def stream(ws: WebSocket, since_seq: int | None = Query(None, ge=0), query_token: str | None = Query(None, alias="token")):
        valid = token_ok(bearer_token(ws.headers.get("authorization"), query_token))
        if not valid or not origin_allowed(ws.headers.get("origin"), ws.headers.get("host"), allowed_origins, bool(token)):
            await ws.close(code=1008)                     # before accept: the client sees HTTP 403
            return
        await ws.accept()
        loop = asyncio.get_running_loop()
        inbox: asyncio.Queue = asyncio.Queue(maxsize=2000)
        overflow = asyncio.Event()

        def put(item: tuple[str, Any]) -> None:
            try:
                inbox.put_nowait(item)
            except asyncio.QueueFull:
                overflow.set()

        def listener(event: str, data: Any) -> None:       # called from any thread
            loop.call_soon_threadsafe(put, (event, data))

        unsubscribe = service.bus.subscribe(listener)
        replayed = since_seq                                  # live messages up to this seq were already sent by the replay

        async def reader() -> None:
            while True:
                frame = await ws.receive_text()
                try:
                    op = json.loads(frame).get("op")
                except (ValueError, AttributeError):
                    continue
                if op == "ping":
                    await ws.send_json({"event": "pong", "data": {}})

        async def writer() -> None:
            while True:
                if overflow.is_set():
                    await ws.close(code=1013)
                    return
                try:
                    event, data = await asyncio.wait_for(inbox.get(), timeout=1.0)
                except asyncio.TimeoutError:
                    continue
                if event == "message" and replayed is not None and data["seq"] <= replayed:
                    continue                                  # already sent during the replay
                await ws.send_json({"event": event, "data": data})

        try:
            await ws.send_json({"event": "hello", "data": {"last_seq": service.store.last_seq(), "health": service.health()}})
            if since_seq is not None:
                position = since_seq
                while True:
                    rows = await loop.run_in_executor(None, lambda: service.store.list(position, None, None, 500))
                    if not rows:
                        break
                    for record in rows:
                        await ws.send_json({"event": "message", "data": record})
                    position = rows[-1]["seq"]
                replayed = position
            tasks = [asyncio.create_task(reader()), asyncio.create_task(writer())]
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            for task in done:
                task.exception()
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            unsubscribe()

    # ------------------------------------------------------------ API docs (offline)
    app.mount("/docs-assets", StaticFiles(directory=str(DOCS_DIR)), name="docs-assets")

    @app.get("/docs", include_in_schema=False)
    def docs():
        return HTMLResponse(DOCS_PAGE)

    @app.get("/openapi.yaml", include_in_schema=False)
    def openapi_yaml():
        return Response(openapi_path().read_text(encoding="utf-8"), media_type="application/yaml")

    @app.get("/openapi.json", include_in_schema=False)
    def openapi_json(request: Request):
        spec = yaml.safe_load(openapi_path().read_text(encoding="utf-8"))
        spec["servers"] = [{"url": str(request.base_url).rstrip("/"), "description": "This gateway"}]
        return Response(json.dumps(spec, default=str), media_type="application/json")

    # ------------------------------------------------------------ dashboard (last: catches everything else)
    @app.get("/{path:path}", include_in_schema=False)
    def dashboard(path: str):
        if path.startswith("v2/"):
            raise ApiError(404, "not_found", f"no such endpoint /{path}")
        index = static_root / "index.html"
        if not index.is_file():
            return HTMLResponse(PLACEHOLDER)
        root = static_root.resolve()
        target = (root / path).resolve() if path else index.resolve()
        if target != root and root not in target.parents:
            raise ApiError(404, "not_found", "no such file")
        if target.is_file():
            return FileResponse(target)
        if "." not in Path(path).name:                       # a route of the single-page app
            return FileResponse(index)
        raise ApiError(404, "not_found", f"no such file /{path}")

    return app
