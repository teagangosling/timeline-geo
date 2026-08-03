"""Great-circle interpolation, used to draw a plausible route for any trip
with no recorded GPS track (typically flights, which Timeline logs as a
single start/end pair) - a straight line in lat/lng space is only a great
circle near the equator, so for a long trip (e.g. a transpacific flight) it
cuts across whatever continents happen to lie between the two longitudes
instead of following the actual over-water path."""
from __future__ import annotations

import math

_DEFAULT_POINTS = 64


def great_circle_points(
    lat1: float, lng1: float, lat2: float, lng2: float, num_points: int = _DEFAULT_POINTS
) -> list[tuple[float, float]]:
    """Returns `num_points` (lat, lng) pairs evenly spaced along the great
    circle from (lat1, lng1) to (lat2, lng2), via spherical linear
    interpolation. Longitude is unwrapped (allowed outside +-180) so a path
    crossing the antimeridian renders as one continuous line instead of
    jumping across the whole map."""
    phi1, lam1 = math.radians(lat1), math.radians(lng1)
    phi2, lam2 = math.radians(lat2), math.radians(lng2)

    angular_dist = 2 * math.asin(
        math.sqrt(
            math.sin((phi2 - phi1) / 2) ** 2
            + math.cos(phi1) * math.cos(phi2) * math.sin((lam2 - lam1) / 2) ** 2
        )
    )
    if angular_dist < 1e-9:
        return [(lat1, lng1), (lat2, lng2)]

    points = []
    prev_lng = None
    for i in range(num_points):
        f = i / (num_points - 1)
        a = math.sin((1 - f) * angular_dist) / math.sin(angular_dist)
        b = math.sin(f * angular_dist) / math.sin(angular_dist)
        x = a * math.cos(phi1) * math.cos(lam1) + b * math.cos(phi2) * math.cos(lam2)
        y = a * math.cos(phi1) * math.sin(lam1) + b * math.cos(phi2) * math.sin(lam2)
        z = a * math.sin(phi1) + b * math.sin(phi2)
        lat = math.degrees(math.atan2(z, math.sqrt(x * x + y * y)))
        lng = math.degrees(math.atan2(y, x))
        if prev_lng is not None:
            while lng - prev_lng > 180:
                lng -= 360
            while lng - prev_lng < -180:
                lng += 360
        points.append((lat, lng))
        prev_lng = lng
    return points
