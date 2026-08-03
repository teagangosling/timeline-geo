# Legacy migration and maintenance tools

One-shot scripts for moving curated data out of the retired bespoke FastAPI
timeline app and into this GeoPulse fork, plus the ongoing city-photo cache job.

- `migrate_legacy.py` — CLI with four subcommands: `place-names`,
  `custom-places`, `city-photos`, `verify`. Every subcommand takes `--dry-run`,
  and every subcommand is **strictly read-only against the legacy database**
  (the legacy connection is opened with `read_only = True`, so a stray write
  aborts rather than mutating a database that is now the only copy of some of
  this data).
- `fetch_city_photos.py` — run after each import; fills `city_photos` with
  Wikimedia imagery and attribution. Requires `--write` to write anything.

```
python -m venv tools/.venv
tools/.venv/Scripts/python -m pip install -r tools/requirements.txt
```

## Connection details

No script has a default DSN, on purpose — a default eventually points at
production. Pass them explicitly, or set:

| Variable | Meaning |
|---|---|
| `GEOPULSE_LEGACY_DSN` | legacy timeline Postgres. Opened read-only. |
| `GEOPULSE_TARGET_DSN` | GeoPulse Postgres. |

For `fetch_city_photos.py`, `GEOPULSE_TARGET_DSN` must be a **read-write** role,
not the public app's SELECT-only role.

---

## ⚠️ The one ordering rule

**Run `place-names` BEFORE re-importing `location-history.json`.**

The importer names stays through the Google place-name cache. If that cache is
empty at import time, every stay is a miss, and the import burns real Google
Places API calls against a 10 000/month free-tier quota resolving names the
legacy database already knows. Running it afterwards still populates the cache,
but the calls are already spent — and re-importing does **not** fix it, because
`gps_points` upserts with `ON CONFLICT DO NOTHING`: a second import over
existing rows is a no-op that enriches nothing. To recover you would have to
re-import in CLEAR mode.

---

## Runbook

### 1. `place-names` — populate the name cache (before anything else)

```
python tools/migrate_legacy.py place-names --dry-run
python tools/migrate_legacy.py place-names
```

Copies legacy `place_names` into `google_place_names` (created by migration
`V90.1.0`), and carries the current month's row from legacy `geocode_quota`
into `google_places_quota`, so the cutover month is not double-spent against the
same Google quota by two different apps.

`osm:`-keyed rows are **dropped deliberately**. Their key is a coordinate
rounded to 4 decimal places with no bounding box, so there is nothing to
translate them into; forcing them into the geocoding cache would create rows
with a NULL `bounding_box`, which match by point-radius alone and would silently
mis-name neighbouring stays forever. They were low-confidence OSM guesses to
begin with — GeoPulse will simply reverse-geocode those stays itself.

### 2. History re-import — through the GeoPulse UI, not this tool

History is **not** migrated by table translation, and no subcommand here does
it. Two reasons:

- `timeline_stays` has no independent existence. It is *derived* from
  `gps_points` plus your stay-detection preferences, and the streaming engine
  regenerates it — discarding anything hand-inserted that differs from what it
  would have produced. Rows written by a migration script would evaporate at the
  first regeneration.
- Legacy `visits` and `activities` are literally Google's own segments, i.e. the
  exact input GeoPulse's importer already consumes. Translating them would be a
  worse-quality copy of a job the importer does properly.

So: log into GeoPulse and import `location-history.json` through the normal
import UI, with the Google Timeline importer. Then wait for timeline generation
to finish before continuing.

### 3. `custom-places` — polygons → AREA favorites (lossy; triage required)

```
python tools/migrate_legacy.py custom-places --dry-run
python tools/migrate_legacy.py custom-places --api-url https://geopulse.example --token "$JWT"
```

Get `$JWT` from `POST /api/auth/api-login`, or use `--api-key` with a service
token from the API-tokens page.

Legacy `custom_places` are arbitrary polygons. GeoPulse AREA favorites are
**bounding boxes only** — two corners. Each polygon therefore becomes its
`ST_Envelope` and is POSTed to `POST /api/favorites/bulk`.

This is deliberately not fixed by extending GeoPulse. Polygon AREAs would mean
touching AREA matching in the location resolver, the bulk favorites API, the
frontend drawing tools, and the semantics of
`geopulse.favorites.max-distance-from-area` — a permanent, high-friction merge
tax on upstream's busiest naming path, paid forever, for a handful of hand-drawn
shapes.

The `--dry-run` output is the point of this subcommand. It lists, **worst
first**, every place with:

- the inflation ratio `ST_Area(ST_Envelope(bounds)) / ST_Area(bounds)` — how
  much dead space the box adds;
- the envelope's width × height in metres;
- the polygon's vertex count.

An over-wide envelope silently claims unrelated stays as that place, forever,
and nothing in the UI will ever tell you it happened. So:

| Ratio | Do this |
|---|---|
| ≤ 1.3 | auto-convert; the box is close enough to the shape |
| > 1.3 | triage by hand |

Triage options, per place:

- **Really one building** → add a POINT favorite at the centroid instead. The
  dry run prints centroid coordinates for exactly this.
- **An L-shape, crescent, or campus** → draw 2–3 smaller AREAs by hand in the UI.
- **The extra area is genuinely empty** (a field, a lake, your own back garden)
  → harmless; re-run with `--max-ratio` raised.

Only places at or below `--max-ratio` (default 1.3) are sent. `--include-triage`
sends the rest un-triaged; it exists for completeness, not as a recommendation.
Places whose envelope has zero width or height are skipped entirely —
GeoPulse's `ValidAreaBoundsValidator` requires NE strictly greater than SW on
both axes. Add those as POINT favorites by hand.

Adding favorites queues a timeline regeneration job; the names only appear on
stays once it completes.

### 4. `city-photos` — copy the photo cache across

```
python tools/migrate_legacy.py city-photos --dry-run
python tools/migrate_legacy.py city-photos
```

Straight row copy into the `city_photos` table created by `V90.3.0` (identical
DDL to legacy). A row copy rather than `pg_dump -t city_photos --data-only |
psql`, because the table is standalone (no foreign keys) and tiny, and the row
copy gives a real `--dry-run`, the same connection handling as every other
subcommand, and `ON CONFLICT DO NOTHING` so it is safe to interleave with
`fetch_city_photos.py` runs. `pg_dump` would additionally need a client-side
version matching the legacy server — one more thing to be wrong at cutover.

Attribution columns (`artist`, `license_name`, `license_url`) are copied
verbatim. They are not optional: nearly all Wikimedia Commons imagery is CC BY
or CC BY-SA, and showing a photo without its author and licence is a licence
violation.

### 5. `fetch_city_photos.py` — fill the gaps

```
python tools/fetch_city_photos.py --geopulse-dsn "$GEOPULSE_TARGET_DSN"
python tools/fetch_city_photos.py --geopulse-dsn "$GEOPULSE_TARGET_DSN" --write
```

Derives the set of cities implied by your stays (nearest known city per stay
coordinate, via `reverse_geocoder` — the same library and mode the retired app
used, so the keys match the rows migrated in step 4), finds the ones with no
cached row, and looks each up on Wikipedia. Misses are cached as misses, so a
city Wikipedia has no photo for is only ever queried once.

Run it again after each subsequent import.

Two things not to change:

- **`curl_cffi`, not `requests`/`httpx`.** Wikimedia's edge fingerprints the TLS
  handshake and 403s plain Python clients regardless of `User-Agent`. curl and
  browsers pass; bare Python clients do not.
- **This script is the only writer of `city_photos`.** The public app reads the
  table with a SELECT-only role and returns `{"photo_url": null}` on a miss.
  Do not grant it write access to close the miss itself: that role's
  read-only-ness *is* the privacy boundary for the public view — enforced by the
  database rather than by application code, which is the whole reason the public
  app exists as a separate app instead of a GeoPulse `ShareType`.

### 6. `verify` — sanity report

```
python tools/migrate_legacy.py verify --user-email you@example.com
```

Reads both databases (writes neither) and prints:

- stays per year: legacy `visits` vs GeoPulse `timeline_stays`;
- total trip distance: legacy `SUM(activities.distance_m)` vs
  `SUM(timeline_trips.distance_meters)`;
- stays named from a Google place: legacy `visits` with a non-null `place_name`
  vs `timeline_stays` with `location_source = 'GOOGLE_PLACE'`.

**A few percent of difference is expected, and so is rather more than a few.**
GeoPulse re-derives stays and trips from the raw points using *your*
stay-detection preferences; the legacy numbers were Google's own pre-segmented
output. Different parameters, different segment counts, slightly different
distances. That is the migration working correctly.

Only rows flagged `*** GAP` — roughly 3× or more either way, or one side at zero
— indicate a real problem: a year that failed to import, a place-name cache
populated after the import instead of before, a units mistake. Do not chase
anything else.

---

## What is lost, and why

| Lost | Why |
|---|---|
| `activity_snaps` | OSM map-matched trip geometry, cached per legacy activity id. GeoPulse has no equivalent concept, and the ids are meaningless after a re-import. GeoPulse draws trips from the raw points instead. |
| Manual place-name edits not in the cache | If you renamed a visit in the old UI and the edit only ever landed in `visits.place_name` rather than `place_names`, it does not come across. Re-express the ones you care about as favorites — favorites outrank Google place names in the naming chain, which is the correct home for a human override anyway. |
| `place_names` rows keyed `osm:<lat>,<lng>` | Rounded coordinates with no bounding box; migrating them would poison the geocoding cache. See step 1. |
| Custom-place polygon shapes | Reduced to bounding boxes. See step 3. |
| `path_points`, `visits`, `activities`, `ingests`, `pending_place_lookups`, `activity_snaps` as tables | Not migrated at all — history is re-imported from source. See step 2. |

Stay *timings and groupings* will also not match the old app exactly. That is
not a loss so much as a different (and configurable) stay-detection algorithm;
tune it in GeoPulse's timeline preferences rather than trying to reproduce the
old segmentation.
