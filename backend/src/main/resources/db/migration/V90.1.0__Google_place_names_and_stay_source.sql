-- FORK: Google Places API (New) place-name cache, monthly quota ledger and deferred-lookup queue,
-- plus the placeID that named a stay.

-- Resolved place names, keyed by Google placeID and cached forever.
-- A NULL name is a *confirmed miss*: Google was asked and had nothing. It must never be re-billed,
-- which is why presence of the row - not nullness of the name - is the cache hit test.
CREATE TABLE google_place_names
(
    place_id    text                     NOT NULL,
    name        text,
    source      varchar(16)              NOT NULL,
    resolved_at timestamptz              NOT NULL DEFAULT now(),
    CONSTRAINT pk_google_place_names PRIMARY KEY (place_id)
);

-- One row per calendar month (UTC, 'YYYY-MM'). Reserved atomically before each billable call so a
-- concurrent race cannot overshoot the configured monthly limit.
CREATE TABLE google_places_quota
(
    month         char(7) NOT NULL,
    request_count integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_google_places_quota PRIMARY KEY (month)
);

-- placeIDs we wanted to resolve but could not, because the month's quota was already spent.
-- Drained by PendingGooglePlaceLookupScheduler once quota frees up.
CREATE TABLE google_place_lookup_pending
(
    place_id  text        NOT NULL,
    latitude  double precision,
    longitude double precision,
    queued_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_google_place_lookup_pending PRIMARY KEY (place_id)
);

CREATE INDEX idx_google_place_lookup_pending_queued_at
    ON google_place_lookup_pending (queued_at);

-- The placeID whose Google name won for this stay. NULL for every stay named any other way.
ALTER TABLE timeline_stays
    ADD COLUMN google_place_id text;

CREATE INDEX idx_timeline_stays_google_place_id
    ON timeline_stays (google_place_id)
    WHERE google_place_id IS NOT NULL;

-- location_source carries an inline CHECK from V1.1.0 that predates GOOGLE_PLACE. Postgres named
-- it timeline_stays_location_source_check; DROP ... IF EXISTS keeps this safe if it was ever
-- renamed.
ALTER TABLE timeline_stays
    DROP CONSTRAINT IF EXISTS timeline_stays_location_source_check;

ALTER TABLE timeline_stays
    ADD CONSTRAINT timeline_stays_location_source_check
        CHECK (location_source IN ('FAVORITE', 'GEOCODING', 'HISTORICAL', 'GOOGLE_PLACE'));
