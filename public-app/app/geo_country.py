"""Offline country lookup for a lat/lng, used to count distinct countries
visited for the stats tab. Does real point-in-polygon lookup against bundled
Natural Earth admin-0 country borders (data/ne_50m_admin_0_countries.geojson),
built into an STRtree once at import - the same "build an offline index once"
pattern tz.py uses for timezones.

`nearest_cities` below is unrelated: it uses reverse_geocoder's nearest-known-
city dataset on purpose, to blur an exact visit down to "nearest city" for the
public stats app. That's a plain nearest-neighbor lookup with no border
awareness, so its `cc` must not be used as a country label (a point can be
nearer a foreign border city than any city in its own country) - country_for/
countries_for are the only source of truth for which country a point is in."""
from __future__ import annotations

import json
from pathlib import Path

import reverse_geocoder as rg
from shapely.geometry import Point, shape
from shapely.strtree import STRtree

_DATA_PATH = Path(__file__).parent / "data" / "ne_50m_admin_0_countries.geojson"

with _DATA_PATH.open(encoding="utf-8") as f:
    _geojson = json.load(f)

def _iso_code(props: dict) -> str | None:
    """Natural Earth uses the literal string "-99" in ISO_A2 as a "no code
    assigned" placeholder (disputed territories, dependencies split out from
    their parent, etc). ISO_A2_EH ("de-facto" variant) fills in the real code
    for most of these (France, Norway, Kosovo, ...); the handful still left
    as "-99" after that (Somaliland, Northern Cyprus, Siachen Glacier) have no
    real ISO code at all, so we return None and let them be skipped rather
    than showing the raw "-99" placeholder in the countries-visited list."""
    code = props["ISO_A2"]
    if code == "-99":
        code = props.get("ISO_A2_EH", "-99")
    return None if code == "-99" else code


_geoms = [shape(feature["geometry"]) for feature in _geojson["features"]]
_iso_codes = [_iso_code(feature["properties"]) for feature in _geojson["features"]]
_tree = STRtree(_geoms)

_geocoder = rg.RGeocoder(mode=1, verbose=False)

# The bundled 50m-resolution coastlines are simplified enough that small
# islands/atolls can fall just outside their country's polygon even though
# the point is really on land (e.g. an outer Maldives atoll far from the
# vertices the 50m dataset kept for that archipelago). If a point isn't
# contained by any polygon, fall back to the nearest one within this radius
# (in degrees; ~220km at the equator) rather than reporting no country.
_FALLBACK_MAX_DEGREES = 2.0

# Enclave micro-states are small enough that the 50m dataset can't render
# their borders precisely, so a point genuinely inside one (e.g. the Vatican
# Museums) can land just outside its own tiny polygon while still falling
# inside the solid (non-holed) polygon of the country that surrounds it -
# without this, such a point would be misreported as the surrounding
# country. Check these first, within a tight radius, before the general
# contains-check below.
_ENCLAVE_CODES = ["VA", "SM"]
_ENCLAVE_MAX_DEGREES = 0.05  # ~5.5km
_enclave_idx = {code: _iso_codes.index(code) for code in _ENCLAVE_CODES}


def country_for(lat: float, lng: float) -> str | None:
    """Returns the ISO country code of the border polygon containing this
    point, or None if it doesn't fall inside any (e.g. international waters)."""
    point = Point(lng, lat)

    for code, idx in _enclave_idx.items():
        if _geoms[idx].distance(point) <= _ENCLAVE_MAX_DEGREES:
            return code

    for idx in _tree.query(point):
        if _geoms[idx].contains(point):
            return _iso_codes[idx]

    idxs, dists = _tree.query_nearest(point, max_distance=_FALLBACK_MAX_DEGREES, return_distance=True)
    best: tuple[float, str] | None = None
    for idx, dist in zip(idxs, dists):
        code = _iso_codes[idx]
        if code is not None and (best is None or dist < best[0]):
            best = (dist, code)
    return best[1] if best else None


def countries_for(points: list[tuple[float, float]]) -> list[str]:
    """Returns the ISO country code for each (lat, lng) point, in the same
    order, skipping points that don't fall inside any country's borders."""
    return [code for lat, lng in points if (code := country_for(lat, lng)) is not None]


def nearest_cities(points: list[tuple[float, float]]) -> list[dict]:
    """Returns the nearest known city to each (lat, lng) point, in the same
    order, as {name, admin1, cc, lat, lng} - the city's own coordinates, not
    the input point. Used by the public stats app to show "one dot per
    city" instead of exact visited coordinates. `cc` here is only the nearest
    city's country, not necessarily the country the input point is in - see
    the module docstring."""
    if not points:
        return []
    return [
        {
            "name": r["name"],
            "admin1": r["admin1"],
            "cc": r["cc"],
            "lat": float(r["lat"]),
            "lng": float(r["lon"]),
        }
        for r in _geocoder.query(points)
    ]
