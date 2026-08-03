-- FORK: Google Timeline exports carry a placeID per visit. The import path writes it into the
-- existing gps_points.telemetry jsonb column, so no INSERT statement needs to change (see FORK.md,
-- "Why placeID lives in telemetry jsonb, not its own column"). These generated columns expose it
-- for indexing and querying.
--
-- google_visit_synthetic is compared as text rather than cast with ::boolean: a cast is not
-- guaranteed to be accepted as immutable inside a generated-column expression.

ALTER TABLE gps_points
    ADD COLUMN google_place_id text
        GENERATED ALWAYS AS (telemetry ->> 'googlePlaceId') STORED,
    ADD COLUMN google_visit_synthetic boolean
        GENERATED ALWAYS AS (COALESCE(telemetry ->> 'googleVisitSynthetic', '') = 'true') STORED;

CREATE INDEX idx_gps_points_google_place_id
    ON gps_points (user_id, google_place_id)
    WHERE google_place_id IS NOT NULL;
