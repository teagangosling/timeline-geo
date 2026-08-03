-- Cached representative photo per city, shown next to the "cities visited"
-- list. Ported unchanged in shape from the retired app's schema.sql so an
-- existing city_photos table can be copied across row-for-row.
--
-- Keyed by "name|country_code" - the same key the retired app used, built
-- from reverse_geocoder's nearest-city result (see public-app/app/city_photos.py).
-- admin1 is deliberately not part of the key; it only ever disambiguated the
-- Wikipedia title during a fetch, never the cache identity.
--
-- photo_url/source_url are NULL when Wikipedia had no usable image for that
-- city. A row with NULLs is a *negative* cache entry, not a missing row, so a
-- known miss is never re-queried.
--
-- artist / license_name / license_url come from the Commons file's
-- extmetadata and must be rendered wherever photo_url is rendered. Practically
-- everything on Commons is CC BY or CC BY-SA, under which attribution is a
-- licence condition - dropping these columns would make displaying the images
-- a licence violation, not merely a cosmetic regression.
--
-- Written only by tools/fetch_city_photos.py, running as the read-write user.
-- The public app reads it through the SELECT-only geopulse_ro role.
CREATE TABLE IF NOT EXISTS city_photos
(
    lookup_key   TEXT PRIMARY KEY,
    photo_url    TEXT,
    source_url   TEXT,
    artist       TEXT,
    license_name TEXT,
    license_url  TEXT,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE city_photos IS
    'Wikimedia city imagery cache, keyed "name|country_code". NULL photo_url is a cached miss.';
COMMENT ON COLUMN city_photos.artist IS
    'Commons extmetadata Artist. Required attribution for CC BY / CC BY-SA imagery - do not drop.';
COMMENT ON COLUMN city_photos.license_name IS
    'Commons extmetadata LicenseShortName. Required attribution - do not drop.';
COMMENT ON COLUMN city_photos.license_url IS
    'Commons extmetadata LicenseUrl. Required attribution - do not drop.';
