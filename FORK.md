# Fork notes

This is a personal fork of [tess1o/geopulse](https://github.com/tess1o/geopulse), carrying four
features migrated from a retired bespoke timeline app:

1. **Google `placeID` decoding** — Google Timeline exports carry a `placeID` per visit. Upstream
   parses it and throws it away. This fork persists it and uses it to name stays via the Google
   Places API (New), which gives real POI names instead of reverse-geocoded street addresses.
2. **Source precedence / gap-fill merge** — this instance ingests both periodic Google Timeline
   exports and a live Home Assistant feed. Upstream has no cross-source reconciliation, so the two
   would interleave into one stream and fragment stay detection. This fork picks one authoritative
   source per time window.
3. **A public read-only aggregate view** — a separate FastAPI app (`public-app/`) serving a coarse,
   shareable overview. Deliberately *not* a GeoPulse `ShareType`: it connects with a SELECT-only
   Postgres role, so the privacy boundary is enforced by the database rather than by application
   code.
4. **City photos** — Wikimedia imagery with attribution, shown in the public view.

Licence: upstream is BSL 1.1 (Change Date 2075-01-01, then AGPL v3). Personal, non-commercial,
self-hosted use is explicitly granted. Commercial/production-for-a-business use is not.

---

## Ground rules

These exist to keep `git merge upstream/main` cheap. Upstream is active — it shipped
`V36.41.0` while this fork was being planned.

### 1. Migrations use the `V90.x.0__` namespace

Upstream is in the `V36.x` range and still climbing. A fork migration numbered `V36.42.0` will
eventually collide with an upstream file of the same version, and Flyway resolves that as a
checksum/ordering failure on an already-migrated database — painful to unwind in production.
`V90+` is free indefinitely.

| Migration | Purpose |
|---|---|
| `V90.0.0__Google_place_id_on_gps_points.sql` | generated columns for placeID + synthetic-visit flag |
| `V90.1.0__Google_place_names_and_stay_source.sql` | place-name cache, quota, pending queue, `timeline_stays.google_place_id` |
| `V90.2.0__Gps_source_merge.sql` | `gps_points.suppressed` + partial index |
| `V90.3.0__City_photos.sql` | city photo cache with attribution columns |

### 2. New code goes in new packages

New packages never conflict on merge. All fork code lives in:

- `backend/.../geocoding/googleplaces/` — Places API client, cache, quota
- `backend/.../streaming/service/googleplace/` — placeID → stay resolution
- `backend/.../gps/merge/` — source precedence engine
- `public-app/` — the read-only public view (top level, not under `backend/`)
- `tools/` — one-shot migration and maintenance scripts

### 3. Edits to upstream files are enumerated and marked

Every edit to a file that came from upstream is bracketed with a `// FORK: <why>` comment, and
listed in the table below. Never reformat an upstream file; never reorder its imports. Both produce
merge conflicts with no semantic content.

| Upstream file | Edit | WS |
|---|---|---|
| `gps/integrations/googletimeline/model/GoogleTimelineGpsPoint.java` | add `placeId`, `syntheticVisitPoint` fields | 1 |
| `gps/integrations/googletimeline/StreamingGoogleTimelineParser.java` | thread `placeId` into `interpolateVisitPoints` | 1 |
| `importdata/service/GoogleTimelineImportStrategy.java` | populate the `telemetry` map | 1 |
| `importdata/service/BaseGpsImportStrategy.java` | call `reconcile()` after write, before timeline generation | 2 |
| `streaming/engine/TimelineEventFinalizationService.java` | apply Google place name below favorites | 1 |
| `streaming/service/converters/StreamingTimelineConverter.java` | `getLocationSource` learns `GOOGLE_PLACE` | 1 |
| `streaming/model/domain/LocationSource.java` | add `GOOGLE_PLACE` constant | 1 |
| `streaming/model/domain/Stay.java` | add `googlePlaceId` field | 1 |
| `streaming/model/domain/GPSPoint.java` | carry the synthetic-visit flag | 1 |
| `streaming/model/entity/TimelineStayEntity.java` | add `googlePlaceId` field | 1 |
| `gps/repository/GpsPointRepository.java` | `AND NOT gp.suppressed` on the timeline-generation queries | 2 |
| `admin/service/SystemSettingsService.java` | register the fork's settings keys | 1, 2 |
| `docker-compose-dev.yml` | build backend from the JVM Dockerfile, not native | 0 |

**No frontend files are modified.** The stay-level `LocationSource` enum
(`FAVORITE`/`GOOGLE_PLACE`/`GEOCODING`/`HISTORICAL`) is backend-only: it is written to
`timeline_stays.location_source` and read by the merge and naming logic, but it is not carried on
any DTO and never reaches the UI. The `locationSource` identifiers in `frontend/src` are a
different concept entirely — GPS *source* configuration (OwnTracks, Home Assistant…) and note
location provenance. Adding `GOOGLE_PLACE` to those maps would be wrong.

**Read but deliberately not edited:**
`importdata/service/BatchProcessor.java` and
`shared/exportimport/NativeSqlImportTemplates.java` — see the design note below.
`shared/service/LocationPointResolver.java` — its signature takes `List<Point>` and it is the
highest-traffic file in the naming chain; the fork resolves placeIDs *alongside* it rather than
changing it.

### 4. Branch layout

- `main` — pristine mirror of `upstream/main`. Never commit here.
- `teagan/main` — long-lived work branch, the fork's default.

```
git fetch upstream
git checkout main && git merge --ff-only upstream/main
git checkout teagan/main && git merge main
```

---

## Design notes worth keeping

### Why `placeID` lives in `telemetry` jsonb, not its own column

`GpsPointEntity` already has a `telemetry` jsonb column, and it is already written by the import
path. `V90.0.0` exposes the placeID as:

```sql
ADD COLUMN google_place_id text GENERATED ALWAYS AS (telemetry ->> 'googlePlaceId') STORED
```

A generated column indexes and queries exactly like a real one, but cannot appear in an INSERT — so
the import path needs no schema-aware changes at all.

The alternative, a plain column, would require edits to
`NativeSqlImportTemplates.GPS_POINTS_INSERT_OR_UPDATE` (a hand-written SQL string) *and* to the
positional parameter binding loop in `BatchProcessor.bulkUpsertGpsPoints`. Those two must stay in
lockstep, they are in a high-churn area, and a mismatch fails silently — the column simply stays
null. Not worth it for zero functional gain.

### `ON CONFLICT DO NOTHING`, not `DO UPDATE`

`GPS_POINTS_INSERT_OR_UPDATE` is named misleadingly and the surrounding comments in
`BatchProcessor` describe behaviour the SQL does not have. The actual clause is:

```sql
ON CONFLICT (user_id, timestamp, coordinates) DO NOTHING
```

**Consequence:** re-importing an export over points that are already present does not enrich them.
If a Google export is imported *before* the Places integration is enabled, those rows keep a null
`google_place_id` forever — a second import of the same file is a no-op. To backfill, re-import in
CLEAR mode or delete the affected range first.

This is fine for the initial migration (a fresh database, so every row is an insert) but it is a
trap later.

### Naming precedence: favorite → Google placeID → geocoding cache → external geocoder

Google place names sit *below* favorites, not above. A favorite is an explicit human override
("Home"); a placeID is machine-derived and will cheerfully label a house with a business name. The
retired app made the same call — its display view was `COALESCE(custom_place.name, place_name)`.

### The unique index does not include `source_type`

`idx_gps_points_no_duplicates` is `(user_id, timestamp, coordinates)`. A Google point and a Home
Assistant point at identical coordinates *and* the same microsecond collide, and the second is
dropped by `DO NOTHING`. Probability is negligible in practice (real GPS fixes vary in the
sub-degree digits). **Do not widen this index** — it would change upstream dedup semantics and
become a permanent merge tax for no real benefit.

### Suppression flags, rather than deleting losing points

The merge engine marks non-authoritative points `suppressed = true` instead of deleting them, and
only the timeline-generation query filters on it. Raw-point map and export endpoints keep showing
everything. This keeps priority reconfiguration reversible: change the order, re-run the
reconciler, and the previous decision is fully undone.
