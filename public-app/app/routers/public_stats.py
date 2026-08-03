"""Backs the public, unauthenticated stats app (see ``public_main.py``) - a
coarse, shareable overview of where the user has been.

What it deliberately never returns:

* **place names.** ``timeline_stays.location_name`` and ``favorite_id`` are
  never selected. A favourite is literally the thing being hidden ("Home",
  "Work"), and a geocoded ``location_name`` is a street address.
* **exact coordinates.** Stay coordinates *are* read - they have to be - but
  they go straight into :func:`geo_country.countries_for` and
  :func:`geo_country.nearest_cities` and are discarded. What leaves the
  process is the *city's own* coordinates, from an offline dataset. A visit
  is therefore blurred to "nearest known city".
* **trip geometry, at all.** No route lines are published. See below.

None of the above is the actual security boundary, though - the boundary is
that this process connects as a SELECT-only Postgres role that has been
granted three tables and nothing else (``db/grants-public-ro.sql``). The
filtering here is defence in depth on top of that.

Why there are no trip lines
---------------------------
Earlier versions published route geometry with two mitigations, and neither
worked:

1. *Trimming ``PUBLIC_TRIP_CLIP_M`` metres from each end.* Trimming removes a
   fraction of the line's **length**, but idling and GPS jitter near home
   accumulate length without displacement. Measured on the real dataset, 500 m
   of trimmed path still ended 4 m from the house, and 788 published trips
   passed within 200 m of it - many of them simple drive-bys with no endpoint
   there to trim at all.
2. *Subtracting a redaction circle around every favourite.* This did remove the
   points, and made things worse: a perfectly circular hole centred on the
   house is a bullseye. It does not conceal the address, it advertises it, and
   the radius tells you how big the secret is.

The failure is not in the tuning, it is in the premise: a route track is a
record of a private routine, and any geometry detailed enough to be worth
drawing is detailed enough to locate a home. So the map shows *where* - the
cities and countries visited - and never *how you got there*. Aggregate
distance by travel mode is still published; it reveals nothing positional.

GeoPulse is multi-user, so **every** statement below filters
``user_id = :uid`` (``PUBLIC_USER_ID``). An unfiltered query would publish
every account on the instance.
"""
from datetime import date

from fastapi import APIRouter, Query
from sqlalchemy import text

from ..activity_type import normalize_activity_type
from ..city_photos import get_city_photo
from ..date_range import date_range_bounds
from ..db import PUBLIC_USER_ID, engine
from ..geo_country import countries_for, nearest_cities
from ..iso_countries import COUNTRY_NAMES

router = APIRouter(prefix="/api", tags=["public-stats"])

# A trip's stored `timestamp` is its start; its end is start + trip_duration
# seconds (V2.2.0 standardised every duration column to seconds). Overlap
# with the requested window is therefore start < :end AND end > :start, the
# same half-open comparison the retired app used against explicit
# start_time/end_time columns.
_OVERLAPS_TRIP = "timestamp < :end AND timestamp + make_interval(secs => trip_duration) > :start"
_OVERLAPS_STAY = "timestamp < :end AND timestamp + make_interval(secs => stay_duration) > :start"

# Distances are metres: V2.2.0 dropped timeline_trips.distance_km in favour
# of distance_meters BIGINT. Aggregated in SQL, then grouped again in Python
# because several GeoPulse movement types collapse onto one display mode.
_DISTANCE_AND_MODE = text(
    f"""
    -- ::double precision because SUM(bigint) is numeric, which asyncpg hands
    -- back as Decimal - and Decimal + float raises. The retired app summed a
    -- double column and got floats for free.
    SELECT movement_type, SUM(distance_meters)::double precision AS distance_m
    FROM timeline_trips
    WHERE user_id = :uid AND {_OVERLAPS_TRIP}
    GROUP BY movement_type
    """
)

# Feeds nearest_cities()/countries_for() only. These coordinates are never
# serialised - see the module docstring.
_STAY_LOCATIONS_IN_RANGE = text(
    f"""
    SELECT ST_Y(location) AS lat, ST_X(location) AS lng
    FROM timeline_stays
    WHERE user_id = :uid AND {_OVERLAPS_STAY}
    """
)

# No trip geometry is published - see the module docstring. Trips are still read
# for the aggregate distance-by-mode figures, which carry no positional
# information: a total of kilometres walked says nothing about where.
#
# This query replaced one that rebuilt each route from gps_points, clipped its
# ends and subtracted redaction circles. Deleting it removed the public app's
# only reason to read gps_points and favorite_locations at all, so both were
# revoked from the read-only role (db/grants-public-ro.sql). The safest handling
# of route data turned out to be not fetching it.

# Stands in for the retired app's `ingests` table: GeoPulse has no import
# ledger, and the newest stay is the same thing the footer wanted to convey
# ("data is current as of ..."). A timestamp, not a location.
#
# Reads timeline_stays rather than gps_points: with route geometry gone, the
# raw-fix table is no longer granted to this role at all.
_LAST_POINT = text("SELECT MAX(timestamp) AS last_updated FROM timeline_stays WHERE user_id = :uid")


@router.get("/last-updated")
async def last_updated():
    """Timestamp of the most recent GPS fix - shown in the frontend footer.
    A date, not a location, so it is safe to expose unauthenticated."""
    async with engine.connect() as conn:
        row = (await conn.execute(_LAST_POINT, {"uid": PUBLIC_USER_ID})).first()
    return {"last_updated": row.last_updated.isoformat() if row and row.last_updated else None}


@router.get("/city-photo")
async def city_photo(name: str = Query(...), country_code: str = Query(...)):
    """Cached Wikimedia photo for a city, or ``{"photo_url": null, ...}`` on a
    miss. Read-only: the cache is populated out-of-band by
    ``tools/fetch_city_photos.py`` running as the read-write user, and this
    app's role could not INSERT even if it tried.

    ``artist``/``license_name``/``license_url`` come back with the URL and
    must be displayed alongside it - Commons imagery is overwhelmingly CC BY
    or CC BY-SA, where attribution is a licence term."""
    async with engine.connect() as conn:
        return await get_city_photo(conn, name=name, country_code=country_code)


@router.get("/public-stats")
async def public_stats(
    from_: date | None = Query(None, alias="from"),
    to: date | None = Query(None),
):
    start, end = date_range_bounds(from_, to)
    window = {"uid": PUBLIC_USER_ID, "start": start, "end": end}

    async with engine.connect() as conn:
        mode_rows = (await conn.execute(_DISTANCE_AND_MODE, window)).all()
        stay_rows = (await conn.execute(_STAY_LOCATIONS_IN_RANGE, window)).all()

    distance_by_mode: dict[str, float] = {}
    for row in mode_rows:
        if not row.distance_m:
            continue
        key = normalize_activity_type(row.movement_type)
        distance_by_mode[key] = distance_by_mode.get(key, 0.0) + row.distance_m
    by_mode = {mode: round(distance_m / 1000, 1) for mode, distance_m in distance_by_mode.items()}
    total_km = round(sum(row.distance_m or 0 for row in mode_rows) / 1000, 1)

    stay_points = [(row.lat, row.lng) for row in stay_rows]
    codes = set(countries_for(stay_points))
    cities: dict[str, dict] = {}
    for city in nearest_cities(stay_points):
        key = f"{city['name']}|{city['admin1']}|{city['cc']}"
        cities.setdefault(
            key,
            {
                "name": city["name"],
                "admin1": city["admin1"],
                "country_code": city["cc"],
                "lat": city["lat"],
                "lng": city["lng"],
            },
        )

    countries = sorted(
        ({"code": code, "name": COUNTRY_NAMES.get(code, code)} for code in codes),
        key=lambda c: c["name"],
    )

    # `trips` stays in the response as an empty FeatureCollection rather than
    # being removed: public-web/app.js reads it unconditionally, and the shape
    # is the retired app's contract. Nothing is published into it - see the
    # module docstring for why route geometry cannot be made safe by trimming.
    trip_features: list = []

    return {
        "distance_km": total_km,
        "by_mode": by_mode,
        "countries": countries,
        "cities": list(cities.values()),
        "trips": {"type": "FeatureCollection", "features": trip_features},
    }
