#!/usr/bin/env python3
"""Populates the `city_photos` cache with Wikimedia imagery + attribution.

This is the standalone, run-it-yourself version of the retired timeline app's
`app/city_photos.py`, which fetched photos lazily inside a web request. Here it
is an offline maintenance job, run once after each history import, because in
this fork the public app connects with a SELECT-only Postgres role.

    THIS SCRIPT IS THE ONLY WRITER OF `city_photos`.

    On a cache miss the public app returns {"photo_url": null} and moves on. Do
    NOT be tempted to give the public app write access so it can close the miss
    itself: that role's read-only-ness IS the privacy boundary for the public
    view -- it is enforced by the database rather than by application code, which
    is the entire reason the public app is a separate app instead of a GeoPulse
    ShareType. Granting INSERT to fix a missing thumbnail would trade that
    guarantee for a cosmetic improvement.

HTTP is done with curl_cffi, impersonating Chrome. This is NOT interchangeable
with `requests` or `httpx`: Wikimedia's edge returns a generic "please respect
our robot policy" 403 to plain Python HTTP clients regardless of what
User-Agent you send, evidently fingerprinting the TLS handshake itself. curl
and real browsers pass; bare Python clients do not. Keep curl_cffi.

Attribution (artist / license name / license URL) is captured from each Commons
file's extmetadata and stored alongside the photo. Almost all Commons media is
CC BY or CC BY-SA; displaying the image without credit is a licence violation,
not a missing nicety. Rows are written with all attribution fields or not at
all.

Writes require --write. The default is a dry run.

    python tools/fetch_city_photos.py --geopulse-dsn "postgresql://..." --write
"""

from __future__ import annotations

import argparse
import html
import os
import re
import sys
import time
from urllib.parse import unquote

try:
    import psycopg
except ImportError:  # pragma: no cover
    sys.exit("psycopg (v3) is required: python -m pip install -r tools/requirements.txt")

try:
    from curl_cffi.requests import Session
    from curl_cffi.requests.exceptions import RequestException
except ImportError:  # pragma: no cover
    sys.exit(
        "curl_cffi is required and is NOT substitutable with requests/httpx -- "
        "Wikimedia blocks plain Python TLS fingerprints.\n"
        "    python -m pip install -r tools/requirements.txt"
    )

try:
    import reverse_geocoder as rg
except ImportError:  # pragma: no cover
    sys.exit("reverse_geocoder is required: python -m pip install -r tools/requirements.txt")

SUMMARY_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/{title}"
COMMONS_API_URL = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "geopulse-fork city-photo cache (personal use)"

HTML_TAG_RE = re.compile(r"<[^>]+>")

# Coordinate rounding used only to shrink the reverse_geocoder input; ~1.1 km at
# the equator, far below the spacing of distinct cities.
COORD_ROUND = 2


def strip_html(value: str | None) -> str | None:
    if not value:
        return None
    return html.unescape(HTML_TAG_RE.sub("", value)).strip() or None


def fetch_summary(session: Session, title: str) -> dict | None:
    resp = session.get(
        SUMMARY_URL.format(title=title.replace(" ", "_")),
        impersonate="chrome",
        headers={"User-Agent": USER_AGENT},
        timeout=30,
    )
    if resp.status_code != 200:
        return None
    data = resp.json()
    if data.get("type") == "disambiguation":
        return None
    photo_url = data.get("thumbnail", {}).get("source")
    if not photo_url:
        return None
    return {
        "photo_url": photo_url,
        "source_url": data.get("content_urls", {}).get("desktop", {}).get("page"),
        "original_url": data.get("originalimage", {}).get("source"),
    }


def fetch_commons_license(session: Session, original_url: str) -> dict:
    """Attribution for a Commons original-image URL, from its extmetadata.

    Best-effort: returns all-None on a non-Commons file or an API error, exactly
    as the retired app did. A photo with no attribution still gets cached (so we
    do not re-query a known-uncreditable file), but the public view is expected
    to suppress display of anything it cannot credit.
    """
    empty = {"artist": None, "license_name": None, "license_url": None}
    if "/wikipedia/commons/" not in original_url:
        return empty

    filename = unquote(original_url.rsplit("/", 1)[-1])
    try:
        resp = session.get(
            COMMONS_API_URL,
            impersonate="chrome",
            headers={"User-Agent": USER_AGENT},
            params={
                "action": "query",
                "titles": f"File:{filename}",
                "prop": "imageinfo",
                "iiprop": "extmetadata",
                "format": "json",
            },
            timeout=30,
        )
    except RequestException:
        return empty
    if resp.status_code != 200:
        return empty

    pages = resp.json().get("query", {}).get("pages", {})
    for page in pages.values():
        infos = page.get("imageinfo")
        if not infos:
            continue
        meta = infos[0].get("extmetadata", {})
        return {
            "artist": strip_html(meta.get("Artist", {}).get("value")),
            "license_name": meta.get("LicenseShortName", {}).get("value"),
            "license_url": meta.get("LicenseUrl", {}).get("value"),
        }
    return empty


def lookup_city(session: Session, name: str, country_name: str | None,
                admin1: str | None) -> dict:
    """Same title-guessing ladder as the retired app.

    Wikipedia titles most non-famous places "City, Region" ("Victoria, British
    Columbia"), so try that first; then "City, Country"; then the bare name,
    which is how capitals and famous cities are titled ("Tokyo"). First title
    that resolves to a page with a thumbnail wins.
    """
    titles = [f"{name}, {admin1}"] if admin1 else []
    if country_name:
        titles.append(f"{name}, {country_name}")
    titles.append(name)

    result = None
    try:
        for title in titles:
            result = fetch_summary(session, title)
            if result is not None:
                break
    except RequestException:
        result = None

    license_info = {"artist": None, "license_name": None, "license_url": None}
    if result and result.get("original_url"):
        license_info = fetch_commons_license(session, result["original_url"])

    return {
        "photo_url": result["photo_url"] if result else None,
        "source_url": result["source_url"] if result else None,
        **license_info,
    }


def load_cities(conn: psycopg.Connection, user_id: str | None) -> list[dict]:
    """Distinct cities implied by this instance's stays.

    Cities are derived the same way the retired app derived them and the public
    app still does: nearest known city per stay coordinate via reverse_geocoder,
    keyed "name|cc". Deriving them any other way (e.g. a nearest-neighbour join
    against geonames_city) would produce keys that do not match the cache rows
    migrated over by migrate_legacy.py, and every legacy row would silently miss.
    """
    sql = "SELECT ST_Y(location), ST_X(location) FROM timeline_stays WHERE location IS NOT NULL"
    params: tuple = ()
    if user_id:
        sql += " AND user_id = %s"
        params = (user_id,)
    with conn.cursor() as cur:
        cur.execute(sql, params)
        points = sorted({(round(lat, COORD_ROUND), round(lon, COORD_ROUND))
                         for lat, lon in cur.fetchall()})
    if not points:
        return []

    country_names: dict[str, str] = {}
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass('geonames_country') IS NOT NULL")
        if cur.fetchone()[0]:
            cur.execute("SELECT iso_alpha2, country_name FROM geonames_country")
            country_names = {cc: nm for cc, nm in cur.fetchall()}

    # mode=1 (single-threaded) matches the retired app's RGeocoder construction
    # exactly, so the keys this produces line up with the migrated cache rows.
    geocoder = rg.RGeocoder(mode=1, verbose=False)
    cities: dict[str, dict] = {}
    for r in geocoder.query(points):
        key = f"{r['name']}|{r['cc']}"
        entry = cities.setdefault(key, {
            "key": key,
            "name": r["name"],
            "cc": r["cc"],
            "admin1": r["admin1"] or None,
            "country_name": country_names.get(r["cc"]),
            "stays": 0,
        })
        entry["stays"] += 1
    return sorted(cities.values(), key=lambda c: -c["stays"])


def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        prog="fetch_city_photos.py",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--geopulse-dsn",
                   help="GeoPulse Postgres DSN, READ-WRITE (or GEOPULSE_TARGET_DSN). "
                        "Must NOT be the public app's read-only role.")
    p.add_argument("--user-id", help="restrict to one GeoPulse user's stays")
    p.add_argument("--limit", type=int, help="fetch at most N missing cities this run")
    p.add_argument("--delay", type=float, default=1.0,
                   help="seconds between Wikimedia requests (default: %(default)s). "
                        "Be polite; this is someone else's free API.")
    p.add_argument("--write", action="store_true",
                   help="actually write to city_photos. Without this it is a dry run.")
    args = p.parse_args(argv)

    dsn = args.geopulse_dsn or os.environ.get("GEOPULSE_TARGET_DSN")
    if not dsn:
        sys.exit("No GeoPulse connection string. Pass --geopulse-dsn or set "
                 "GEOPULSE_TARGET_DSN. There is deliberately no default.")

    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT to_regclass('city_photos') IS NOT NULL")
            if not cur.fetchone()[0]:
                sys.exit("Table 'city_photos' does not exist; it is created by "
                         "migration V90.3.0. Start the GeoPulse backend once first.")
            cur.execute("SELECT lookup_key FROM city_photos")
            cached = {row[0] for row in cur.fetchall()}

        cities = load_cities(conn, args.user_id)
        missing = [c for c in cities if c["key"] not in cached]
        print(f"cities implied by stays : {len(cities)}")
        print(f"already cached          : {len(cities) - len(missing)}")
        print(f"missing                 : {len(missing)}")

        if args.limit:
            missing = missing[:args.limit]
        if not missing:
            print("nothing to fetch.")
            return 0

        if not args.write:
            print("\n[dry run] would look up (most-visited first); pass --write to fetch:")
            for c in missing:
                admin = f", {c['admin1']}" if c["admin1"] else ""
                print(f"  {c['stays']:>6} stays  {c['name']}{admin} ({c['cc']})")
            return 0

        hits = misses = 0
        with Session() as session:
            for i, c in enumerate(missing):
                row = lookup_city(session, c["name"], c["country_name"], c["admin1"])
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO city_photos (lookup_key, photo_url, source_url,
                                                 artist, license_name, license_url)
                        VALUES (%(key)s, %(photo_url)s, %(source_url)s,
                                %(artist)s, %(license_name)s, %(license_url)s)
                        ON CONFLICT (lookup_key) DO NOTHING
                        """,
                        {"key": c["key"], **row},
                    )
                conn.commit()
                if row["photo_url"]:
                    hits += 1
                    credit = row["artist"] or "UNCREDITED"
                    print(f"  + {c['key']:<40} {row['license_name'] or '?'} / {credit}")
                else:
                    misses += 1
                    # Cached as a negative result on purpose: without it every
                    # run re-queries the same city Wikipedia has no photo for.
                    print(f"  - {c['key']:<40} no photo (cached as a miss)")
                if args.delay and i < len(missing) - 1:
                    time.sleep(args.delay)

        print(f"\nfetched {hits} photo(s), recorded {misses} miss(es).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
