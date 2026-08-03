#!/usr/bin/env python3
"""One-shot migration of curated data from the retired FastAPI timeline app
into this GeoPulse fork.

Location *history* is deliberately NOT migrated by this tool. See tools/README.md:
`timeline_stays` has no independent existence (it is derived from `gps_points`
plus the user's stay-detection preferences, and the streaming engine will
regenerate -- i.e. discard -- anything hand-inserted), and the legacy
`visits`/`activities` tables are literally Google's own segments, which is the
same input GeoPulse's importer already consumes. So history is *re-imported*
from `location-history.json` through GeoPulse's normal import UI, and this tool
handles everything around that re-import.

    ORDERING REQUIREMENT
    --------------------
    Run `place-names` BEFORE the history re-import. The importer resolves stay
    names through the Google place-name cache; if the cache is empty at import
    time every single stay is a cache miss and the import burns Google Places
    API quota (10k/month) resolving names this tool could have handed it for
    free. Running it afterwards still populates the cache but the calls are
    already spent, and `ON CONFLICT DO NOTHING` on gps_points means re-importing
    does not enrich rows that already exist.

Every subcommand supports --dry-run, and every subcommand is strictly READ-ONLY
against the legacy database (the legacy connection is opened with
`read_only = True`, so a stray write fails loudly rather than mutating a
retired-but-still-precious database).

Connection details come from CLI args or environment variables. There are no
defaults -- in particular no default that points at a production database.

    --legacy-dsn    / GEOPULSE_LEGACY_DSN    source (read-only)
    --geopulse-dsn  / GEOPULSE_TARGET_DSN    destination

Subcommands:
    place-names     place_names + geocode_quota -> google_place_names + google_places_quota
    custom-places   custom_places polygons -> GeoPulse AREA favorites (lossy, see below)
    city-photos     city_photos -> city_photos (straight row copy)
    verify          post-migration sanity report, legacy vs GeoPulse
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

try:
    import psycopg
except ImportError:  # pragma: no cover - dependency hint
    sys.exit(
        "psycopg (v3) is required. Install the tool dependencies:\n"
        "    python -m pip install -r tools/requirements.txt"
    )

BATCH = 1000

# GeoPulse's google_place_names.source is varchar(16); legacy place_names.source
# is an unconstrained TEXT holding 'google' or 'osm'. Guard the width so a
# surprise value fails here with a clear message rather than at INSERT time.
SOURCE_MAX_LEN = 16

# AddAreaToFavoritesDto.name is @Size(max = 100).
FAVORITE_NAME_MAX_LEN = 100

# Recommended auto-convert threshold for the polygon -> bounding-box conversion.
DEFAULT_MAX_RATIO = 1.3


# --------------------------------------------------------------------------
# connections
# --------------------------------------------------------------------------

def resolve_dsn(value: str | None, env_var: str, what: str) -> str:
    dsn = value or os.environ.get(env_var)
    if not dsn:
        sys.exit(
            f"No {what} connection string. Pass --{what}-dsn or set {env_var}.\n"
            "There is deliberately no default: a default would eventually point "
            "at production."
        )
    return dsn


def connect_legacy(args) -> psycopg.Connection:
    """Open the legacy database READ ONLY.

    `read_only = True` puts the session in a read-only transaction, so any
    accidental INSERT/UPDATE/DDL in this tool raises instead of mutating the
    retired app's database. The legacy database is the only surviving copy of
    some of this data; treat it as a museum piece.
    """
    conn = psycopg.connect(resolve_dsn(args.legacy_dsn, "GEOPULSE_LEGACY_DSN", "legacy"))
    conn.read_only = True
    return conn


def connect_geopulse(args) -> psycopg.Connection:
    return psycopg.connect(
        resolve_dsn(args.geopulse_dsn, "GEOPULSE_TARGET_DSN", "geopulse")
    )


def table_exists(conn: psycopg.Connection, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass(%s) IS NOT NULL", (table,))
        return bool(cur.fetchone()[0])


def require_legacy_tables(conn: psycopg.Connection, *tables: str) -> None:
    missing = [t for t in tables if not table_exists(conn, t)]
    if missing:
        sys.exit(
            f"Legacy table(s) {', '.join(missing)} not found in the database "
            "given by --legacy-dsn. Check you are pointed at the retired "
            "timeline app's database and not something else."
        )


def require_table(conn: psycopg.Connection, table: str, migration: str) -> None:
    if not table_exists(conn, table):
        sys.exit(
            f"Target table '{table}' does not exist. It is created by migration "
            f"{migration}; run the GeoPulse backend once so Flyway applies it, "
            "then re-run this command."
        )


# --------------------------------------------------------------------------
# place-names
# --------------------------------------------------------------------------

def cmd_place_names(args) -> int:
    """legacy place_names -> google_place_names, plus the current month's quota.

    Legacy `place_names` is keyed by `lookup_key`, which is EITHER a real Google
    place_id OR a synthetic 'osm:<lat>,<lng>' key (coordinates rounded to 4
    decimal places) for visits that had no place_id.

    The osm: rows are DROPPED, deliberately. Their key is a rounded coordinate
    with no bounding box, so there is nothing to translate them into: GeoPulse's
    `google_place_names` is keyed by real Google place_id, and pushing rounded
    coordinates into the geocoding cache instead would create cache entries with
    a NULL `bounding_box`, which poisons the cache -- a bounding-box-less entry
    matches by point-radius only and will silently mis-name neighbouring stays
    forever. The loss is small: an osm: row is a low-confidence OSM guess in the
    first place, and GeoPulse will simply reverse-geocode those stays itself.
    """
    legacy = connect_legacy(args)
    require_legacy_tables(legacy, "place_names", "geocode_quota")
    with legacy, legacy.cursor() as cur:
        cur.execute(
            """
            SELECT lookup_key, name, source, resolved_at
            FROM place_names
            WHERE lookup_key NOT LIKE 'osm:%'
            """
        )
        rows = cur.fetchall()
        cur.execute("SELECT count(*) FROM place_names WHERE lookup_key LIKE 'osm:%'")
        osm_skipped = cur.fetchone()[0]

        month = datetime.now(timezone.utc).strftime("%Y-%m")
        # Legacy columns are (month TEXT PK, google_requests INTEGER) -- note the
        # column is `google_requests`, not `request_count`.
        cur.execute(
            "SELECT google_requests FROM geocode_quota WHERE month = %s", (month,)
        )
        quota_row = cur.fetchone()
        quota_used = quota_row[0] if quota_row else 0

    too_long = [r for r in rows if r[2] is not None and len(r[2]) > SOURCE_MAX_LEN]
    if too_long:
        sys.exit(
            f"{len(too_long)} legacy place_names rows have a `source` longer than "
            f"{SOURCE_MAX_LEN} chars (google_place_names.source is varchar(16)); "
            f"first offender: {too_long[0][2]!r}"
        )

    named = sum(1 for r in rows if r[1])
    print(f"legacy place_names, google-keyed : {len(rows)}")
    print(f"  of which have a resolved name  : {named}")
    print(f"  negative-cache (name IS NULL)  : {len(rows) - named}")
    print(f"legacy place_names, osm: keyed   : {osm_skipped}  (DROPPED, see --help)")
    print(f"geocode_quota for {month}        : {quota_used} Google requests already spent")

    if args.dry_run:
        print("\n[dry-run] nothing written.")
        return 0

    target = connect_geopulse(args)
    with target:
        require_table(target, "google_place_names", "V90.1.0")
        require_table(target, "google_places_quota", "V90.1.0")
        with target.cursor() as cur:
            inserted = 0
            for i in range(0, len(rows), BATCH):
                chunk = rows[i:i + BATCH]
                cur.executemany(
                    """
                    INSERT INTO google_place_names (place_id, name, source, resolved_at)
                    VALUES (%s, %s, %s, %s)
                    ON CONFLICT (place_id) DO NOTHING
                    """,
                    chunk,
                )
                inserted += len(chunk)

            # Carry the cutover month's spend forward so the fresh GeoPulse
            # counter does not let us double-spend against the same 10k/month
            # Google quota. GREATEST() keeps this idempotent and never lowers a
            # count that GeoPulse has already incremented past the legacy value.
            cur.execute(
                """
                INSERT INTO google_places_quota (month, request_count)
                VALUES (%s, %s)
                ON CONFLICT (month) DO UPDATE
                SET request_count = GREATEST(google_places_quota.request_count,
                                             EXCLUDED.request_count)
                """,
                (month, quota_used),
            )
        target.commit()
        with target.cursor() as cur:
            cur.execute("SELECT count(*) FROM google_place_names")
            total = cur.fetchone()[0]

    print(f"\noffered {inserted} rows (duplicates ignored); google_place_names now holds {total}")
    print(f"carried {quota_used} spent Google requests into google_places_quota[{month}]")
    print("\nREMINDER: run this BEFORE re-importing location-history.json.")
    return 0


# --------------------------------------------------------------------------
# custom-places
# --------------------------------------------------------------------------

# Envelope geometry and both area measures, per legacy custom_place.
#
# Areas are computed on the planar geometry in degrees. That is not a real-world
# area, but we only ever use the *ratio* of two areas over the same small
# region, where the latitude distortion cancels out. Width/height are measured
# on the geography type, so those ARE real metres.
CUSTOM_PLACES_SQL = """
WITH e AS (
    SELECT id,
           name,
           bounds::geometry              AS poly,
           ST_Envelope(bounds::geometry) AS env
    FROM custom_places
)
SELECT id,
       name,
       ST_YMax(env) AS ne_lat,
       ST_XMax(env) AS ne_lon,
       ST_YMin(env) AS sw_lat,
       ST_XMin(env) AS sw_lon,
       CASE WHEN ST_Area(poly) > 0
            THEN ST_Area(env) / ST_Area(poly)
            ELSE NULL END AS ratio,
       ST_Distance(
           ST_SetSRID(ST_MakePoint(ST_XMin(env), ST_YMin(env)), 4326)::geography,
           ST_SetSRID(ST_MakePoint(ST_XMax(env), ST_YMin(env)), 4326)::geography
       ) AS width_m,
       ST_Distance(
           ST_SetSRID(ST_MakePoint(ST_XMin(env), ST_YMin(env)), 4326)::geography,
           ST_SetSRID(ST_MakePoint(ST_XMin(env), ST_YMax(env)), 4326)::geography
       ) AS height_m,
       ST_Y(ST_Centroid(poly)) AS centroid_lat,
       ST_X(ST_Centroid(poly)) AS centroid_lon,
       ST_NPoints(poly) AS n_points
FROM e
ORDER BY ratio DESC NULLS FIRST, name
"""


def cmd_custom_places(args) -> int:
    """legacy custom_places (arbitrary polygons) -> GeoPulse AREA favorites.

    THIS IS LOSSY AND INTENTIONALLY SO. A GeoPulse AREA favorite is a bounding
    box -- two corners -- not a polygon. Teaching GeoPulse about polygons would
    mean touching AREA matching in the location resolver, the bulk favorites
    API, the frontend drawing tools, and the semantics of
    `geopulse.favorites.max-distance-from-area`. That is a permanent,
    high-friction merge tax on upstream's highest-traffic naming path, paid
    forever, for a handful of hand-drawn shapes. So instead each polygon becomes
    its ST_Envelope.

    The cost of the envelope is real: an over-wide box silently claims unrelated
    stays as that place, forever, and nothing in the UI will ever tell you it
    happened. Hence the triage below -- the inflation ratio is how much dead
    space the box adds, and anything above ~1.3 deserves a human decision.
    """
    legacy = connect_legacy(args)
    require_legacy_tables(legacy, "custom_places")
    with legacy, legacy.cursor() as cur:
        cur.execute(CUSTOM_PLACES_SQL)
        rows = cur.fetchall()

    if not rows:
        print("legacy custom_places is empty; nothing to do.")
        return 0

    auto, triage, degenerate = [], [], []
    for r in rows:
        (pid, name, ne_lat, ne_lon, sw_lat, sw_lon, ratio,
         width_m, height_m, c_lat, c_lon, n_points) = r
        rec = {
            "id": pid, "name": name, "ne_lat": ne_lat, "ne_lon": ne_lon,
            "sw_lat": sw_lat, "sw_lon": sw_lon, "ratio": ratio,
            "width_m": width_m, "height_m": height_m,
            "centroid_lat": c_lat, "centroid_lon": c_lon, "n_points": n_points,
        }
        if ne_lat <= sw_lat or ne_lon <= sw_lon:
            # GeoPulse's ValidAreaBoundsValidator requires NE strictly greater
            # than SW on BOTH axes; a zero-width/height envelope is rejected.
            rec["verdict"] = "SKIP"
            degenerate.append(rec)
        elif ratio is not None and ratio <= args.max_ratio:
            rec["verdict"] = "auto"
            auto.append(rec)
        else:
            rec["verdict"] = "TRIAGE"
            triage.append(rec)

    # Worst-first: the shapes most damaged by rectangularisation come first.
    print(f"{len(rows)} legacy custom_places -> AREA favorites "
          f"(auto-convert threshold: ratio <= {args.max_ratio})\n")
    header = f"{'ratio':>7}  {'width':>9}  {'height':>9}  {'pts':>4}  {'verdict':<8}  name"
    print(header)
    print("-" * max(len(header), 78))
    for rec in triage + degenerate + auto:
        ratio = "  n/a  " if rec["ratio"] is None else f"{rec['ratio']:7.2f}"
        print(f"{ratio}  {rec['width_m']:8.0f}m  {rec['height_m']:8.0f}m  "
              f"{rec['n_points']:4d}  {rec['verdict']:<8}  {rec['name']}")

    if triage:
        print(f"\n{len(triage)} place(s) above ratio {args.max_ratio} need a human decision.")
        print("For each one, pick whichever is true:")
        print("  * it is really one building     -> add a POINT favorite at the centroid")
        print("    (centroid coordinates are printed below)")
        print("  * it is an L / crescent / campus -> draw 2-3 smaller AREAs by hand in the UI")
        print("  * the extra box area is empty    -> re-run with --max-ratio raised, it is harmless")
        print("\ncentroids for the POINT option:")
        for rec in triage:
            print(f"  {rec['name']}: {rec['centroid_lat']:.6f}, {rec['centroid_lon']:.6f}")

    if degenerate:
        print(f"\n{len(degenerate)} place(s) have a zero-width or zero-height envelope and "
              "cannot become an AREA (GeoPulse requires NE strictly > SW on both axes). "
              "Add these as POINT favorites by hand.")

    to_send = auto if not args.include_triage else auto + triage
    print(f"\nwould POST {len(to_send)} AREA favorite(s) to {args.api_url}/api/favorites/bulk")

    if args.dry_run:
        print("[dry-run] nothing sent.")
        return 0
    if not to_send:
        print("nothing to send.")
        return 0
    if not args.token and not args.api_key:
        sys.exit("Sending requires credentials: pass --token (JWT from "
                 "POST /api/auth/api-login) or --api-key (a service token).")

    areas = []
    for rec in to_send:
        name = rec["name"]
        if len(name) > FAVORITE_NAME_MAX_LEN:
            print(f"  ! truncating name over {FAVORITE_NAME_MAX_LEN} chars: {name!r}")
            name = name[:FAVORITE_NAME_MAX_LEN]
        # Payload shape from AddAreaToFavoritesDto / BulkAddFavoritesDto.
        areas.append({
            "name": name,
            "northEastLat": rec["ne_lat"],
            "northEastLon": rec["ne_lon"],
            "southWestLat": rec["sw_lat"],
            "southWestLon": rec["sw_lon"],
        })

    body = json.dumps({"points": [], "areas": areas}).encode()
    req = urllib.request.Request(
        args.api_url.rstrip("/") + "/api/favorites/bulk",
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    if args.api_key:
        req.add_header("X-API-Key", args.api_key)
    else:
        req.add_header("Authorization", f"Bearer {args.token}")

    try:
        with urllib.request.urlopen(req) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode(errors="replace")
        sys.exit(f"POST /api/favorites/bulk failed: HTTP {exc.code} {detail}")
    except urllib.error.URLError as exc:
        sys.exit(f"POST /api/favorites/bulk failed: {exc.reason}")

    data = payload.get("data", payload)
    print(f"\nsuccess: {data.get('successCount')}  failed: {data.get('failedCount')}")
    if data.get("jobId"):
        print(f"timeline regeneration job: {data['jobId']}")
        print("Favorites only take effect on stays after regeneration completes.")
    for err in (data.get("errors") or []):
        print(f"  ! {err}")
    return 0


# --------------------------------------------------------------------------
# city-photos
# --------------------------------------------------------------------------

def cmd_city_photos(args) -> int:
    """legacy city_photos -> GeoPulse city_photos (identical DDL, V90.3.0).

    Method: a straight row copy through this process, NOT
    `pg_dump -t city_photos --data-only | psql`.

    Why: the table is standalone (no foreign keys), tiny (hundreds of rows), and
    a row copy gives us three things the pipe does not -- a real --dry-run, the
    same connection-argument handling as every other subcommand here, and
    `ON CONFLICT (lookup_key) DO NOTHING`, which makes re-running safe when this
    step is interleaved with fetch_city_photos.py runs. The pg_dump route also
    requires a client-side pg_dump whose major version matches the legacy
    server, which is one more thing to be wrong on a Windows workstation at
    cutover time.

    Attribution columns (artist, license_name, license_url) are copied verbatim.
    They are NOT optional: nearly all Wikimedia Commons imagery is CC BY / CC
    BY-SA, and displaying a photo without its author and licence is a licence
    violation, not a cosmetic omission.
    """
    legacy = connect_legacy(args)
    require_legacy_tables(legacy, "city_photos")
    with legacy, legacy.cursor() as cur:
        cur.execute(
            """
            SELECT lookup_key, photo_url, source_url, artist,
                   license_name, license_url, fetched_at
            FROM city_photos
            """
        )
        rows = cur.fetchall()

    hits = sum(1 for r in rows if r[1])
    attributed = sum(1 for r in rows if r[3] or r[4] or r[5])
    print(f"legacy city_photos rows          : {len(rows)}")
    print(f"  with a photo                   : {hits}")
    print(f"  negative-cache (known miss)    : {len(rows) - hits}")
    print(f"  carrying attribution metadata  : {attributed}")
    if hits and attributed < hits:
        print(f"  ! {hits - attributed} row(s) have a photo but no attribution; "
              "those pre-date the attribution columns. Delete them and let "
              "fetch_city_photos.py re-fetch, or they will display uncredited.")

    if args.dry_run:
        print("\n[dry-run] nothing written.")
        return 0

    target = connect_geopulse(args)
    with target:
        require_table(target, "city_photos", "V90.3.0")
        with target.cursor() as cur:
            for i in range(0, len(rows), BATCH):
                cur.executemany(
                    """
                    INSERT INTO city_photos (lookup_key, photo_url, source_url,
                                             artist, license_name, license_url, fetched_at)
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (lookup_key) DO NOTHING
                    """,
                    rows[i:i + BATCH],
                )
        target.commit()
        with target.cursor() as cur:
            cur.execute("SELECT count(*) FROM city_photos")
            total = cur.fetchone()[0]
    print(f"\noffered {len(rows)} rows (duplicates ignored); city_photos now holds {total}")
    return 0


# --------------------------------------------------------------------------
# verify
# --------------------------------------------------------------------------

def resolve_user_id(conn: psycopg.Connection, args) -> str:
    if args.user_id:
        return args.user_id
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM users WHERE email = %s", (args.user_email,))
        row = cur.fetchone()
    if not row:
        sys.exit(f"No GeoPulse user with email {args.user_email!r}.")
    return row[0]


def _flag(legacy_val: float, new_val: float) -> str:
    """Only order-of-magnitude gaps are worth chasing."""
    if legacy_val == 0 and new_val == 0:
        return "ok"
    if legacy_val == 0 or new_val == 0:
        return "*** GAP"
    ratio = new_val / legacy_val
    if ratio < 0.34 or ratio > 3.0:
        return "*** GAP"
    return "ok"


def cmd_verify(args) -> int:
    """Post-migration sanity report.

    A FEW PERCENT -- even 10-20% -- of difference is EXPECTED and is not a bug.
    Legacy `visits` are Google's own pre-segmented stays; GeoPulse re-derives
    stays from the raw points using YOUR stay-detection preferences (trip
    detection algorithm, minimum stay duration, merge thresholds). Different
    parameters, different segment counts. The same applies to trip distance:
    GeoPulse measures the path it reconstructs, Google reported its own.

    Only order-of-magnitude gaps mean something went wrong -- a missing year, a
    3x distance difference, zero Google-named stays where legacy had thousands.
    Those are flagged '*** GAP'. Everything else is noise; do not chase it.
    """
    legacy = connect_legacy(args)
    target = connect_geopulse(args)

    with legacy, target:
        require_legacy_tables(legacy, "visits", "activities")
        for t in ("users", "timeline_stays", "timeline_trips"):
            if not table_exists(target, t):
                sys.exit(f"Target table '{t}' not found -- is --geopulse-dsn "
                         "really pointed at the GeoPulse database?")
        user_id = resolve_user_id(target, args)

        with legacy.cursor() as cur:
            cur.execute(
                """
                SELECT EXTRACT(YEAR FROM start_time AT TIME ZONE 'UTC')::int AS y, count(*)
                FROM visits GROUP BY y ORDER BY y
                """
            )
            legacy_years = dict(cur.fetchall())
            cur.execute("SELECT COALESCE(SUM(distance_m), 0) / 1000.0 FROM activities")
            legacy_km = float(cur.fetchone()[0])
            cur.execute("SELECT count(*) FROM visits WHERE place_name IS NOT NULL")
            legacy_named = cur.fetchone()[0]

        with target.cursor() as cur:
            cur.execute(
                """
                SELECT EXTRACT(YEAR FROM timestamp AT TIME ZONE 'UTC')::int AS y, count(*)
                FROM timeline_stays WHERE user_id = %s GROUP BY y ORDER BY y
                """,
                (user_id,),
            )
            new_years = dict(cur.fetchall())
            cur.execute(
                "SELECT COALESCE(SUM(distance_meters), 0) / 1000.0 FROM timeline_trips "
                "WHERE user_id = %s",
                (user_id,),
            )
            new_km = float(cur.fetchone()[0])
            cur.execute(
                "SELECT count(*) FROM timeline_stays "
                "WHERE user_id = %s AND location_source = 'GOOGLE_PLACE'",
                (user_id,),
            )
            new_named = cur.fetchone()[0]

    print(f"GeoPulse user: {user_id}")
    print("All years bucketed in UTC on both sides.\n")

    print("STAYS PER YEAR      legacy visits vs GeoPulse timeline_stays")
    print(f"{'year':>6}  {'legacy':>9}  {'geopulse':>9}  {'delta':>9}  flag")
    print("-" * 52)
    for year in sorted(set(legacy_years) | set(new_years)):
        lv = legacy_years.get(year, 0)
        nv = new_years.get(year, 0)
        pct = "n/a" if lv == 0 else f"{(nv - lv) / lv * 100:+.1f}%"
        print(f"{year:>6}  {lv:>9}  {nv:>9}  {pct:>9}  {_flag(lv, nv)}")
    lt, nt = sum(legacy_years.values()), sum(new_years.values())
    pct = "n/a" if lt == 0 else f"{(nt - lt) / lt * 100:+.1f}%"
    print("-" * 52)
    print(f"{'total':>6}  {lt:>9}  {nt:>9}  {pct:>9}  {_flag(lt, nt)}")

    print("\nTOTALS")
    print(f"{'metric':<28}  {'legacy':>12}  {'geopulse':>12}  {'delta':>9}  flag")
    print("-" * 76)
    pct = "n/a" if legacy_km == 0 else f"{(new_km - legacy_km) / legacy_km * 100:+.1f}%"
    print(f"{'trip distance (km)':<28}  {legacy_km:>12,.0f}  {new_km:>12,.0f}  "
          f"{pct:>9}  {_flag(legacy_km, new_km)}")
    pct = "n/a" if legacy_named == 0 else f"{(new_named - legacy_named) / legacy_named * 100:+.1f}%"
    print(f"{'Google-named stays':<28}  {legacy_named:>12,}  {new_named:>12,}  "
          f"{pct:>9}  {_flag(legacy_named, new_named)}")

    print("""
HOW TO READ THIS
  A few percent of difference is EXPECTED, and so is rather more than a few.
  GeoPulse re-derives stays and trips from the raw points using your own
  stay-detection preferences; legacy `visits`/`activities` were Google's own
  pre-segmented output. Different parameters produce different segment counts
  and slightly different distances. That is the migration working correctly.

  Only lines flagged '*** GAP' (roughly 3x or more either way, or one side at
  zero) indicate something actually went wrong: a year that failed to import, a
  place-name cache that was populated after the import instead of before, a
  units mistake. Everything else is noise. Do not chase it.""")
    return 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    epilog = (
        "ORDER MATTERS. Run `place-names` BEFORE re-importing location-history.json\n"
        "through the GeoPulse UI, so the import spends zero Google Places API calls.\n"
        "Full runbook: tools/README.md"
    )
    p = argparse.ArgumentParser(
        prog="migrate_legacy.py",
        description=__doc__,
        epilog=epilog,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = p.add_subparsers(dest="command", required=True)

    def add_common(sp, need_target=True):
        sp.add_argument("--legacy-dsn", help="legacy Postgres DSN (or GEOPULSE_LEGACY_DSN). "
                                             "Opened READ ONLY.")
        if need_target:
            sp.add_argument("--geopulse-dsn", help="GeoPulse Postgres DSN (or GEOPULSE_TARGET_DSN)")
        sp.add_argument("--dry-run", action="store_true",
                        help="report what would happen; write nothing")

    sp = sub.add_parser("place-names", help="place_names + quota -> google_place_names",
                        description=cmd_place_names.__doc__,
                        formatter_class=argparse.RawDescriptionHelpFormatter)
    add_common(sp)
    sp.set_defaults(func=cmd_place_names)

    sp = sub.add_parser("custom-places", help="custom_places -> AREA favorites (lossy)",
                        description=cmd_custom_places.__doc__,
                        formatter_class=argparse.RawDescriptionHelpFormatter)
    add_common(sp, need_target=False)
    sp.add_argument("--api-url", default="http://localhost:8080",
                    help="GeoPulse backend base URL (default: %(default)s)")
    sp.add_argument("--token", help="JWT access token (POST /api/auth/api-login)")
    sp.add_argument("--api-key", help="service API token, sent as X-API-Key")
    sp.add_argument("--max-ratio", type=float, default=DEFAULT_MAX_RATIO,
                    help="auto-convert places whose envelope/polygon area ratio is at "
                         "most this (default: %(default)s)")
    sp.add_argument("--include-triage", action="store_true",
                    help="also send the places above --max-ratio, un-triaged. "
                         "An over-wide box silently claims unrelated stays forever.")
    sp.set_defaults(func=cmd_custom_places)

    sp = sub.add_parser("city-photos", help="copy the city_photos cache across",
                        description=cmd_city_photos.__doc__,
                        formatter_class=argparse.RawDescriptionHelpFormatter)
    add_common(sp)
    sp.set_defaults(func=cmd_city_photos)

    sp = sub.add_parser("verify", help="post-migration sanity report",
                        description=cmd_verify.__doc__,
                        formatter_class=argparse.RawDescriptionHelpFormatter)
    add_common(sp)
    g = sp.add_mutually_exclusive_group(required=True)
    g.add_argument("--user-email", help="GeoPulse account the history was imported into")
    g.add_argument("--user-id", help="GeoPulse user UUID")
    sp.set_defaults(func=cmd_verify)

    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    # `verify` reads both databases but writes neither.
    if args.command == "verify":
        args.dry_run = True
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
