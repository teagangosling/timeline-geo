# `public-app` — the public read-only travel overview

A small, standalone FastAPI service that serves a deliberately coarse,
unauthenticated summary of one GeoPulse user's travel: total distance, a
breakdown by transport mode, which countries were visited, one dot per
*nearest city*, and trip lines with their ends trimmed off.

It is separate from the GeoPulse backend on purpose. See below.

---

## The privacy model

**The boundary is a Postgres grant, not application code.**

This service connects as `geopulse_ro`, a role with `LOGIN`, `CONNECT`,
`USAGE ON SCHEMA public`, and `SELECT` on exactly four tables:
`timeline_stays`, `timeline_trips`, `gps_points`, `city_photos`. Everything
else in the database — `users`, `shared_link`, `favorite_locations`,
`reverse_geocoding_location`, `gps_source_config` (Home Assistant bearer
token), `system_settings` (Google Places API key) — is simply not visible to
it. A SQL-injection bug, a careless future endpoint, or a misconfigured route
cannot reach them, because the credential cannot.

See [`../db/grants-public-ro.sql`](../db/grants-public-ro.sql), which is the
authoritative list and explains why there is no `ALTER DEFAULT PRIVILEGES`
(new upstream tables must fail closed).

### Why not a GeoPulse `ShareType`?

GeoPulse has a sharing feature and it would have been less code. But a share
type runs inside the Quarkus backend, which necessarily holds read-write
credentials for the whole database. The privacy guarantee would then be "the
Java code currently only selects safe columns" — true until someone refactors
a DTO. Here it is "the connection is physically incapable of reading those
tables". That is a much stronger claim and it survives future contributors.

### Layered on top of the grant

1. **Place names are never selected.** `timeline_stays.location_name` and
   `favorite_id` are readable but never appear in a query. A favourite's name
   ("Home") is the exact thing being hidden.
2. **Stay coordinates are blurred to the nearest city, in-process.** Raw
   lat/lng comes out of the database, goes straight into
   `geo_country.nearest_cities()` / `countries_for()`, and is discarded. What
   gets serialised is the *city's own* coordinates from an offline dataset.

   This is why the offline reverse geocoder is kept rather than joining
   GeoPulse's `reverse_geocoding_location` table: that table is full of
   `display_name` street addresses, and granting SELECT on it to buy a
   city name would trade away the entire point.

   Country membership uses real point-in-polygon against bundled Natural
   Earth borders (`app/data/ne_50m_admin_0_countries.geojson`), *not*
   `reverse_geocoder`'s `cc` — a point near a border can be nearest to a
   foreign city.
3. **Trip ends are clipped.** Every published line is trimmed by
   `PUBLIC_TRIP_CLIP_M` metres at each end, in SQL, via `ST_LineSubstring`.
   The retired app returned whole tracks, which start and end on the user's
   driveway — its "no exact coordinates" property was only ever true of
   stays. The clip fraction is capped at `0.4` so a trip shorter than twice
   the clip distance keeps its middle 20% instead of inverting and vanishing.
4. **Multi-user filtering.** GeoPulse is multi-tenant. Every statement filters
   `user_id = :uid` from `PUBLIC_USER_ID`. There is no safe default, so a
   missing `PUBLIC_USER_ID` is a hard startup failure.

### What it still leaks, honestly

- Roughly where you live, at city granularity, and roughly when you travel.
  That is the feature.
- Trip *shapes* between the clipped endpoints. A 500 m clip hides the
  driveway; it does not hide which suburb the road leads into. Raise
  `PUBLIC_TRIP_CLIP_M` if that matters to you.
- Country and city lists are exact, not sampled.

---

## Environment

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `DATABASE_URL` | **yes** | — | `postgresql+asyncpg://geopulse_ro:<pw>@<host>:5432/geopulse`. Must be the read-only role. Nothing here verifies that; the database enforces it. |
| `PUBLIC_USER_ID` | **yes** | — | UUID of the single GeoPulse user to publish. Parsed at import; a malformed value fails at startup, not on first request. |
| `PUBLIC_TRIP_CLIP_M` | no | `500` | Metres trimmed from each end of every trip line. |
| `TILE_STYLE_URL` | no | `""` | MapLibre style URL, handed to the frontend via `/api/config`. Without it the map renders blank. |
| `PUBLIC_WEB_STATIC_DIR` | no | `/public-web` | Where the static frontend lives. The Dockerfile copies `public-web/` there. |

Both required variables are read at *import* time (`app/db.py`) so a
misconfigured container crashes on start rather than silently serving wrong
data. This follows the retired app's compose file, where missing read-only
credentials had to crash the container rather than fall back to the
read-write user.

---

## Running it locally against a GeoPulse database

**1. Apply the fork migrations.** `city_photos` comes from
`backend/src/main/resources/db/migration/V90.3.0__City_photos.sql`, applied by
Flyway when the backend starts. This app never runs migrations and could not:
its role has no DDL rights.

**2. Create the read-only role** (as the database owner):

```bash
psql -h localhost -U geopulse -d geopulse \
     -v ro_password="pick-something-long" \
     -f ../db/grants-public-ro.sql
```

Re-run this (minus `CREATE ROLE`) after **every** migration — Flyway runs as
the read-write user, so anything it creates or recreates carries no grant for
`geopulse_ro`.

**3. Find the user UUID:**

```bash
psql -h localhost -U geopulse -d geopulse -c "SELECT id, email FROM users;"
```

**4. Run it:**

```bash
cd public-app
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.txt

export DATABASE_URL='postgresql+asyncpg://geopulse_ro:pick-something-long@localhost:5432/geopulse'
export PUBLIC_USER_ID='00000000-0000-0000-0000-000000000000'
export PUBLIC_WEB_STATIC_DIR="$PWD/public-web"
export TILE_STYLE_URL='https://…/style.json'

uvicorn app.public_main:app --reload --port 8100
```

Then open <http://localhost:8100/>.

First start takes a few seconds and a few hundred MB of RSS:
`reverse_geocoder` loads its full cities dataset into memory, and
`geo_country` builds an STRtree over the Natural Earth polygons — both once,
at import.

### Or with Docker

```bash
docker build -t geopulse-public-app public-app/
docker run --rm -p 8100:8000 \
  -e DATABASE_URL='postgresql+asyncpg://geopulse_ro:…@host.docker.internal:5432/geopulse' \
  -e PUBLIC_USER_ID='…' \
  -e TILE_STYLE_URL='…' \
  geopulse-public-app
```

Deploy it on its own hostname with no auth in front. It must **not** share a
network namespace or credentials with the GeoPulse backend.

---

## API

| Route | Returns |
|---|---|
| `GET /api/public-stats?from=YYYY-MM-DD&to=YYYY-MM-DD` | `{distance_km, by_mode, countries:[{code,name}], cities:[{name,admin1,country_code,lat,lng}], trips: <GeoJSON FeatureCollection>}`. Both dates optional; omitting both means all time. |
| `GET /api/last-updated` | `{last_updated: <ISO8601 or null>}` — the newest GPS fix. A time, not a place. |
| `GET /api/city-photo?name=&country_code=` | `{photo_url, source_url, artist, license_name, license_url}`, all nullable. Read-only cache lookup; a miss returns all nulls and does **not** trigger a fetch. |
| `GET /api/config` | `{tile_style_url}` |

`artist` / `license_name` / `license_url` must be displayed wherever
`photo_url` is displayed. Commons imagery is overwhelmingly CC BY / CC BY-SA,
where attribution is a licence condition.

The `city_photos` cache is populated out-of-band by `tools/fetch_city_photos.py`
running as the read-write user.

### Transport-mode vocabulary

`trips` features carry an `activity_type` property, and `by_mode` is keyed the
same way. GeoPulse stores `TripType.name()` in `timeline_trips.movement_type`;
`app/activity_type.py` maps it onto the tokens `public-web/app.js` already
colours and labels, so the frontend needed no changes:

| GeoPulse `movement_type` | coarse mode | emitted `activity_type` |
|---|---|---|
| `WALK` | walk | `WALKING` |
| `RUNNING` | walk | `RUNNING` |
| `BICYCLE` | cycle | `CYCLING` |
| `CAR` | drive | `IN_PASSENGER_VEHICLE` |
| `MOTORCYCLE` | drive | `MOTORCYCLING` |
| `TRAIN` | transit | `IN_TRAIN` |
| `FLIGHT` | fly | `FLYING` |
| `BOAT` | other | `SAILING` |
| `UNKNOWN`, NULL, anything unrecognised | other | `UNKNOWN` |

---

## Trip geometry, and a caveat

`timeline_trips.path` is a real PostGIS `LineString`, so the retired app's
`trip_geometry.py` — which reassembled lines by walking a time-ordered
`path_points` table — was dropped as obsolete.

**However:** upstream stopped writing that column. `TimelineTripEntity`
comments it "Since 1.3.0 we don't store path in DB", and nothing in the Java
tree calls `setPath()`. On a current install the column is NULL for every row.

So the query falls back, in this order:

1. `timeline_trips.path`, if present (older rows, imported data);
2. a line rebuilt in SQL from `gps_points` inside the trip's time window —
   `ST_MakeLine(coordinates ORDER BY timestamp)`. Done in the database so raw
   fixes never enter this process; only the clipped result crosses the wire;
3. a great-circle arc between `start_point` and `end_point`
   (`app/great_circle.py`), clipped by the same fraction. This is what
   flights and any gap-covered trip get, and a straight lat/lng line would
   cut across continents on a long haul.

Step 2 is a correlated subquery per trip. It is fine for a personal dashboard
over a year at a time; an all-time query on a large database will be slow. If
that bites, add a `gps_points (user_id, timestamp)` covering index or
materialise the clipped lines.
