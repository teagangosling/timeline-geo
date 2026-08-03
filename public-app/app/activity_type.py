"""Maps GeoPulse's ``timeline_trips.movement_type`` onto the transport-mode
vocabulary the public frontend already renders.

The retired app stored Google Timeline's own activity strings
("IN_PASSENGER_VEHICLE", "CYCLING", ...) and ``normalize_activity_type`` only
canonicalised their casing. GeoPulse instead stores ``TripType.name()`` - a
closed enum of nine values, written by
``streaming/service/converters/StreamingTimelineConverter.java``:

    entity.setMovementType(trip.getTripType() != null ? trip.getTripType().name() : "UNKNOWN");

so the function keeps its name and its call sites but becomes a real mapping.

Two vocabularies are involved and it matters which one goes on the wire:

* the **coarse mode** - ``walk``/``cycle``/``drive``/``transit``/``fly``/
  ``other`` - which is the semantic grouping this public view is *about*;
* the **legacy token** - ``WALKING``/``CYCLING``/``IN_PASSENGER_VEHICLE``/... -
  which is what ``public-web/app.js`` actually keys on, in both
  ``ACTIVITY_COLORS`` (a MapLibre ``match`` expression on the
  ``activity_type`` feature property) and ``fmtModeLabel`` (which
  title-cases the token for the sidebar).

``normalize_activity_type`` returns the **legacy token**, because the brief
requires ``public-web/`` to need zero changes: emitting ``"drive"`` would
fall through ``ACTIVITY_COLORS`` to the grey default and every mode would
render identically. ``coarse_mode`` exposes the six-value grouping for
anything that wants it.
"""
from __future__ import annotations

# GeoPulse TripType -> (coarse mode, legacy token the frontend renders).
#
# Enumerated from streaming/model/shared/TripType.java (and cross-checked
# against frontend/src/utils/timelineIconUtils.js, which lists the same nine
# keys). movement_type is a plain VARCHAR(50) with no CHECK constraint, so
# unrecognised values are possible in principle and fall through to "other".
_BY_MOVEMENT_TYPE: dict[str, tuple[str, str]] = {
    "WALK":       ("walk",    "WALKING"),
    "RUNNING":    ("walk",    "RUNNING"),
    "BICYCLE":    ("cycle",   "CYCLING"),
    "CAR":        ("drive",   "IN_PASSENGER_VEHICLE"),
    "MOTORCYCLE": ("drive",   "MOTORCYCLING"),
    "TRAIN":      ("transit", "IN_TRAIN"),
    "FLIGHT":     ("fly",     "FLYING"),
    # BOAT has no slot in the walk/cycle/drive/transit/fly grouping, so it is
    # "other" - but the legacy frontend does know SAILING (blue), so it still
    # gets a colour of its own rather than the grey default.
    "BOAT":       ("other",   "SAILING"),
    "UNKNOWN":    ("other",   "UNKNOWN"),
}

_FALLBACK = ("other", "UNKNOWN")


def _lookup(movement_type: str | None) -> tuple[str, str]:
    if not movement_type:
        return _FALLBACK
    return _BY_MOVEMENT_TYPE.get(movement_type.strip().upper(), _FALLBACK)


def normalize_activity_type(movement_type: str | None) -> str:
    """GeoPulse ``movement_type`` -> the token ``public-web/app.js`` keys on.

    NULL, empty and unrecognised values all become ``"UNKNOWN"``, which is
    exactly what the retired app returned for a null activity type - so the
    frontend's grey-default path is unchanged.
    """
    return _lookup(movement_type)[1]


def coarse_mode(movement_type: str | None) -> str:
    """GeoPulse ``movement_type`` -> ``walk``/``cycle``/``drive``/``transit``/
    ``fly``/``other``. Not currently serialised; kept because it, not the
    legacy token, is the grouping this view is conceptually built on."""
    return _lookup(movement_type)[0]
