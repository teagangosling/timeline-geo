-- FORK (WS-2): cross-source GPS reconciliation.
--
-- This instance ingests BOTH periodic Google Timeline exports and a live Home Assistant feed.
-- Upstream's duplicate detection only compares points of the SAME source type, so the two feeds
-- interleave into one stream and fragment stay detection.
--
-- The reconciler (org.github.tess1o.geopulse.gps.merge.GpsSourceMergeService) buckets time into
-- 5 minute windows, elects one authoritative source per window, coalesces short runs, and then
-- marks every non-authoritative point suppressed = true. Nothing is deleted, so changing the
-- priority order and re-running fully reverses the previous decision.
--
-- Only the timeline-generation point-loading query filters on this column. Raw-point map
-- endpoints, exports and the friends/live-location paths keep returning everything.
--
-- Deliberately a PLAIN column, not generated: it is written by UPDATE only. The import path uses
-- an explicit column list (NativeSqlImportTemplates.GPS_POINTS_INSERT_OR_UPDATE) that omits it,
-- so the default applies on insert, and that statement is ON CONFLICT DO NOTHING, so a re-import
-- never clobbers a previously computed value.

ALTER TABLE gps_points
    ADD COLUMN IF NOT EXISTS suppressed boolean NOT NULL DEFAULT false;

-- Partial index for the timeline-generation scan, which is always "... AND NOT suppressed".
-- Complements (does not replace) idx_gps_points_timeline_stream_keyset from V36.41.0. That index
-- is (user_id, timestamp, id) over ALL rows and still serves every unfiltered keyset scan
-- (exports, regeneration campaigns, coverage). This one is narrower and much smaller once a
-- Google/HA overlap exists, so the planner should prefer it for the suppressed-filtered scan.
--
-- NOTE: this index stops at timestamp, while the streaming query orders by (timestamp, id).
-- Duplicate timestamps therefore still need a small sort. If that ever shows up in EXPLAIN,
-- widen this index to (user_id, timestamp, id) WHERE NOT suppressed - it is a pure superset.
CREATE INDEX IF NOT EXISTS idx_gps_points_active
    ON gps_points (user_id, timestamp)
    WHERE NOT suppressed;
