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
* **trip endpoints.** Published trip lines are trimmed by
  ``PUBLIC_TRIP_CLIP_M`` metres at each end. The retired app returned whole
  tracks, which begin and end on the user's driveway; its "no exact
  coordinates" property was only ever true of stays, not trips.

None of the above is the actual security boundary, though - the boundary is
that this process connects as a SELECT-only Postgres role that has been
granted four tables and nothing else (``db/grants-public-ro.sql``). The
filtering here is defence in depth on top of that.

GeoPulse is multi-user, so **every** statement below filters
``user_id = :uid`` (``PUBLIC_USER_ID``). An unfiltered query would publish
every account on the instance.
"""
import json
from datetime import date

from fastapi import APIRouter, Query
from sqlalchemy import text

from ..activity_type import normalize_activity_type
from ..city_photos import get_city_photo
from ..date_range import date_range_bounds
from ..db import PUBLIC_TRIP_CLIP_M, PUBLIC_USER_ID, engine
from ..geo_country import countries_for, nearest_cities
from ..great_circle import great_circle_points
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

# Trip lines, clipped and serialised entirely inside Postgres so untrimmed
# geometry never reaches this process.
#
# `clip_frac` is PUBLIC_TRIP_CLIP_M as a fraction of the trip's length,
# capped at 0.4: on a trip shorter than 2 x the clip distance an uncapped
# fraction would exceed 0.5, and ST_LineSubstring with start > end returns
# empty - short trips would silently vanish from the map instead of merely
# being trimmed. The cap means a very short trip keeps its middle 20%.
#
# `path` is COALESCEd with a line rebuilt from gps_points because upstream
# stopped populating it in 1.3.0 (TimelineTripEntity: "Since 1.3.0 we don't
# store path in DB"); nothing in the Java tree calls setPath any more, so on
# a modern install the column is NULL for every row. Rebuilding in SQL keeps
# the raw fixes in the database - only the clipped result crosses the wire.
_TRIPS_IN_RANGE = text(
    f"""
    WITH trips AS (
        SELECT timestamp,
               trip_duration,
               distance_meters,
               movement_type,
               path,
               ST_Y(start_point) AS start_lat, ST_X(start_point) AS start_lng,
               ST_Y(end_point)   AS end_lat,   ST_X(end_point)   AS end_lng,
               LEAST(
                   0.4,
                   CASE WHEN distance_meters > 0
                        THEN :clip_m / distance_meters::double precision
                        ELSE 0.4
                   END
               ) AS clip_frac
        FROM timeline_trips
        WHERE user_id = :uid AND {_OVERLAPS_TRIP}
    ), lines AS (
        SELECT t.*,
               COALESCE(
                   t.path,
                   (SELECT ST_MakeLine(g.coordinates ORDER BY g.timestamp)
                    FROM gps_points g
                    WHERE g.user_id = :uid
                      AND g.timestamp >= t.timestamp
                      AND g.timestamp <= t.timestamp
                                       + make_interval(secs => t.trip_duration))
               ) AS line
        FROM trips t
    )
    SELECT movement_type,
           distance_meters,
           start_lat, start_lng, end_lat, end_lng,
           clip_frac,
           CASE
               WHEN line IS NOT NULL
                AND GeometryType(line) = 'LINESTRING'
                AND ST_NumPoints(line) >= 2
                AND ST_Length(line) > 0
               THEN ST_AsGeoJSON(ST_LineSubstring(line, clip_frac, 1 - clip_frac))
           END AS geojson
    FROM lines
    ORDER BY timestamp
    """
)

# Stands in for the retired app's `ingests` table: GeoPulse has no import
# ledger, and the newest GPS fix is the same thing the footer wanted to
# convey ("data is current as of ..."). A timestamp, not a location.
_LAST_POINT = text("SELECT MAX(timestamp) AS last_updated FROM gps_points WHERE user_id = :uid")


def _clip_points(points: list[tuple[float, float]], clip_frac: float) -> list[tuple[float, float]]:
    """Drop `clip_frac` of a point list from each end, keeping at least two
    points. Used only on the great-circle fallback below; real geometry is
    clipped by ST_LineSubstring in SQL."""
    n = len(points)
    if n < 3 or clip_frac <= 0:
        return points
    cut = int(n * clip_frac)
    trimmed = points[cut : n - cut]
    return trimmed if len(trimmed) >= 2 else points


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
        trip_rows = (await conn.execute(_TRIPS_IN_RANGE, {**window, "clip_m": PUBLIC_TRIP_CLIP_M})).all()

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

    trip_features = []
    for trip in trip_rows:
        if trip.geojson:
            # Already [lng, lat] and already clipped, straight from PostGIS.
            coordinates = json.loads(trip.geojson)["coordinates"]
        else:
            # No usable geometry (no stored path, and no GPS fixes in the
            # window - typically a flight). Draw the great circle between the
            # endpoints instead of a straight lat/lng line, then clip it the
            # same way so the endpoints stay hidden.
            arc = great_circle_points(
                trip.start_lat, trip.start_lng, trip.end_lat, trip.end_lng
            )
            coordinates = [[lng, lat] for lat, lng in _clip_points(arc, trip.clip_frac)]
        if len(coordinates) < 2:
            continue
        trip_features.append(
            {
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": coordinates},
                "properties": {
                    "activity_type": normalize_activity_type(trip.movement_type),
                    "distance_m": trip.distance_meters,
                },
            }
        )

    return {
        "distance_km": total_km,
        "by_mode": by_mode,
        "countries": countries,
        "cities": list(cities.values()),
        "trips": {"type": "FeatureCollection", "features": trip_features},
    }
