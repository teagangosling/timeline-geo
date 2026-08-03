"""Database wiring for the public, unauthenticated stats app.

Two environment variables are mandatory and are read at *import* time, so a
misconfigured container crashes on startup rather than serving wrong (or
over-privileged) data:

* ``DATABASE_URL`` - must point at a **SELECT-only** Postgres role
  (``geopulse_ro``, created by ``db/grants-public-ro.sql``). This mirrors the
  retired app's compose file, where the read-only credentials were required
  and the container was never allowed to silently fall back to the
  read-write user. Nothing here verifies the role is actually read-only -
  that is enforced by the database, which is the whole point of the design.
* ``PUBLIC_USER_ID`` - the UUID of the single GeoPulse user whose data is
  published. GeoPulse is multi-user; every query in this app filters on it.
  Without it there is no safe default (an unfiltered query would publish
  every user's travel), so a missing value is fatal.
"""
from __future__ import annotations

import os
import uuid

from sqlalchemy.ext.asyncio import create_async_engine


def _require(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(
            f"{name} is required. The public app must never start without it - "
            "see public-app/README.md."
        )
    return value


DATABASE_URL = _require("DATABASE_URL")

# Parsed (not just truthiness-checked) so a typo fails here rather than as an
# asyncpg cast error on the first request.
PUBLIC_USER_ID = str(uuid.UUID(_require("PUBLIC_USER_ID")))

# Metres trimmed from each end of every published trip line. The retired app
# returned full tracks, which start and end on the user's driveway - so its
# "no exact coordinates" claim was only partly true. See routers/public_stats.
PUBLIC_TRIP_CLIP_M = float(os.environ.get("PUBLIC_TRIP_CLIP_M", "500"))

# Radius blanked out around every favorite location before any trip line is
# published. End-clipping alone does not keep a home address off the map:
# idling or GPS jitter near home adds path length without adding distance
# (measured on real data - 500m of trimmed path still ended 4m from the house),
# and a trip that merely drives past home has no endpoint there to trim.
# Subtracting a zone around each favorite covers both cases, and any place
# marked as a favorite later is redacted automatically. Set to 0 to disable.
PUBLIC_REDACT_M = float(os.environ.get("PUBLIC_REDACT_M", "500"))

engine = create_async_engine(DATABASE_URL, pool_pre_ping=True)
