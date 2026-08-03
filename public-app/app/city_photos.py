"""Read-only accessor for the ``city_photos`` cache (see migration
``V90.3.0__City_photos.sql``).

The retired app's version of this module *fetched* from Wikipedia/Wikimedia
Commons and wrote the result back. That half now lives in
``tools/fetch_city_photos.py``, which runs out-of-band as the read-write
user. This app only reads: its Postgres role has SELECT and nothing else, so
an INSERT here would fail anyway - the split just makes that explicit.

A cache miss is not an error and does not trigger a fetch; it returns a row
of all-``None`` fields. The frontend treats a null ``photo_url`` as "no
photo".

``artist``/``license_name``/``license_url`` are carried through deliberately.
Almost everything on Commons is CC BY / CC BY-SA, where attribution is a
licence condition rather than a courtesy - a caller that displays
``photo_url`` must display these too.
"""
from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection

_SELECT_CACHED = text(
    "SELECT photo_url, source_url, artist, license_name, license_url "
    "FROM city_photos WHERE lookup_key = :key"
)

_MISS = {
    "photo_url": None,
    "source_url": None,
    "artist": None,
    "license_name": None,
    "license_url": None,
}


def lookup_key(name: str, country_code: str) -> str:
    """The cache key format, unchanged from the retired app so an existing
    ``city_photos`` table migrates across as-is: ``"<name>|<country_code>"``.
    ``admin1`` is deliberately *not* part of the key - it only ever
    disambiguated the Wikipedia title during a fetch."""
    return f"{name}|{country_code}"


async def get_city_photo(conn: AsyncConnection, *, name: str, country_code: str) -> dict:
    """Returns ``{photo_url, source_url, artist, license_name, license_url}``
    (all ``str | None``) for a city, or all-``None`` on a cache miss."""
    row = (await conn.execute(_SELECT_CACHED, {"key": lookup_key(name, country_code)})).first()
    if row is None:
        return dict(_MISS)
    return dict(row._mapping)
