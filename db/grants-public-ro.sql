-- Read-only Postgres role for public-app/ (the unauthenticated, coarse travel
-- overview). This file *is* the privacy boundary for that app: it is enforced
-- by the database, not by application code, which is why the public view was
-- built as a separate service rather than as a GeoPulse ShareType.
--
-- Run as a superuser / the database owner:
--
--     psql -h <host> -U geopulse -d geopulse \
--          -v ro_password="$(cat ro_password.txt)" \
--          -f db/grants-public-ro.sql
--
-- (CREATE ROLE fails if geopulse_ro already exists; drop it first, or comment
--  that statement out and re-run only the GRANTs.)

CREATE ROLE geopulse_ro LOGIN PASSWORD :'ro_password';

GRANT CONNECT ON DATABASE geopulse TO geopulse_ro;
GRANT USAGE  ON SCHEMA public TO geopulse_ro;
GRANT SELECT ON timeline_stays, timeline_trips, city_photos TO geopulse_ro;

-- Neither gps_points nor favorite_locations is granted any more.
--
-- Both were needed only to build published route lines: gps_points to rebuild
-- the track, favorite_locations (geometry columns only) to subtract a redaction
-- zone from it. Route geometry is no longer published at all, so both grants
-- were revoked. Dropping a capability turned out to be the only mitigation that
-- actually worked - see public-app/app/routers/public_stats.py.
--
-- If they were ever re-granted, gps_points is every raw fix ever recorded, at
-- full precision. That is the most sensitive table in the database.


-- ---------------------------------------------------------------------------
-- No ALTER DEFAULT PRIVILEGES. On purpose.
-- ---------------------------------------------------------------------------
-- ALTER DEFAULT PRIVILEGES ... GRANT SELECT ON TABLES would make every table
-- upstream adds in future readable by this role the moment Flyway creates it.
-- Upstream is active and ships tables regularly; a future migration adding,
-- say, a device-token or a raw-geocoding-response table would silently become
-- publicly readable with no change to this repo and no review.
--
-- Fail-closed instead: a new table is inaccessible until someone adds it to
-- the GRANT above by hand. The cost is that a fork feature needing a new
-- table also needs an edit here. That is the correct direction to fail.

-- ---------------------------------------------------------------------------
-- Tables that must NEVER be granted to geopulse_ro
-- ---------------------------------------------------------------------------
--   gps_points                  every raw GPS fix at full precision. Formerly
--                               granted to rebuild route lines; revoked once
--                               those stopped being published.
--   favorite_locations          user-assigned names ("Home", "Work", "Mum's")
--                               are exactly what this view exists to hide. The
--                               geometry columns were briefly granted for
--                               redaction; revoked with the same change.
--   users                       password hashes, email addresses, the UUID->
--                               person mapping this whole design exists to
--                               avoid publishing.
--   shared_link                 share tokens. Readable tokens = every private
--                               timeline share on the instance is now public.
--   gps_source_config           holds the Home Assistant bearer token (and
--                               OwnTracks/Overland credentials) in the clear.
--   system_settings             holds the Google Places API key - a billable
--                               credential.
--   reverse_geocoding_location  full display_name strings, i.e. street
--                               addresses for every stay. Granting this would
--                               undo the offline-reverse-geocoder design in
--                               public-app/app/geo_country.py, which exists so
--                               coordinates become a city name inside the
--                               process and never touch a table of addresses.
--   google_place_names          resolved POI names per placeID (fork table,
--                               V90.1.0) - same exposure as above, plus
--                               business names.
--
-- Also never grant, for the same class of reason: user_api_tokens,
-- mobile_auth_codes, oidc_session_states, user_oidc_connections,
-- oidc_providers, audit_log, user_invitations, friend_invitations,
-- user_friends, timeline_notes.
--
-- The granted tables are the minimum the public app needs:
--   timeline_stays   coordinates, blurred to the nearest city before output,
--                    plus MAX(timestamp) for the "last updated" footer
--   timeline_trips   distance and movement type only - no geometry is read
--   city_photos      Wikimedia imagery cache (read-only; tools/ writes it)

-- ---------------------------------------------------------------------------
-- Re-run this file after every migration
-- ---------------------------------------------------------------------------
-- Flyway runs as the read-write user, so tables it creates are owned by that
-- user and carry no grant for geopulse_ro. If a migration DROPs and recreates
-- one of the granted tables above (upstream has done exactly this - see
-- V3.0.0's column rewrites), the grant is lost with the old table and the
-- public app starts returning 500s on "permission denied for table ...".
-- Re-running this file (minus CREATE ROLE) is idempotent and cheap.
