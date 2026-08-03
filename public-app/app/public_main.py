"""Separate, unauthenticated ASGI app serving a coarse, shareable travel
overview - see ``routers/public_stats.py`` for exactly what it does and does
not expose.

Unlike the retired app, this does not share an image with anything: GeoPulse's
own backend is a Quarkus service, and this stays a standalone FastAPI
container. It never runs migrations (Flyway, run by the backend as the
read-write user, owns the schema - including
``V90.3.0__City_photos.sql``) and it cannot: its Postgres role has SELECT and
nothing else.

Deliberately *not* implemented as a GeoPulse ``ShareType`` or Vue route. A
share type would run inside the backend, holding read-write credentials, with
the privacy boundary living in Java code that any future refactor could walk
through. Here the boundary is a Postgres grant.
"""
import os

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

# Imported before anything else so a missing DATABASE_URL / PUBLIC_USER_ID
# aborts startup rather than surfacing on the first request.
from .routers import public_stats

app = FastAPI(title="GeoPulse Public Stats")

app.include_router(public_stats.router)


@app.get("/api/config")
async def config():
    return {"tile_style_url": os.environ.get("TILE_STYLE_URL", "")}


# Static frontend last, so /api/* routes above take precedence.
app.mount(
    "/",
    StaticFiles(directory=os.environ.get("PUBLIC_WEB_STATIC_DIR", "/public-web"), html=True),
    name="public-web",
)
