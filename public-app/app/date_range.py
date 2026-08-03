"""Shared helper for turning optional `from`/`to` query dates into UTC
datetime bounds, used by any endpoint that filters by a date range
(heatmap, heatmap-paths, stats)."""
from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone


def date_range_bounds(from_: date | None, to: date | None) -> tuple[datetime, datetime]:
    start = datetime.combine(from_, time.min, tzinfo=timezone.utc) if from_ else datetime.min.replace(tzinfo=timezone.utc)
    end = (
        datetime.combine(to, time.min, tzinfo=timezone.utc) + timedelta(days=1)
        if to
        else datetime.max.replace(tzinfo=timezone.utc)
    )
    return start, end
