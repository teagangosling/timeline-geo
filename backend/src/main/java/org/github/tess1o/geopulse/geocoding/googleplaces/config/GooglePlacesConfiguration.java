package org.github.tess1o.geopulse.geocoding.googleplaces.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.Config;
import org.github.tess1o.geopulse.admin.service.SystemSettingsService;

/**
 * Configuration for the Google Places API (New) place-name integration.
 * <p>
 * Follows the same DB-first / env-fallback pattern as
 * {@link org.github.tess1o.geopulse.geocoding.config.GeocodingConfigurationService}, with one
 * deliberate difference: the API key is <em>not</em> a system setting.
 * <p>
 * Every other provider credential in GeoPulse lives (encrypted) in {@code system_settings}, which
 * is admin-editable and travels in database dumps. This key is directly billable per request, so it
 * is read from the {@code GEOPULSE_GOOGLE_PLACES_API_KEY} environment variable only. There is no
 * code path that can write it, read it back over the admin API, or carry it into a backup.
 * <p>
 * The two non-secret settings are looked up through {@link SystemSettingsService} so an admin can
 * override them in the database, but they are <em>not</em> registered in that class's
 * {@code SETTING_DEFINITIONS} map - registering them would mean editing an upstream file for no
 * functional gain. An unregistered key returns an empty string, so the env/default fallback is
 * applied here instead.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.REQUIRES_NEW)
@Slf4j
public class GooglePlacesConfiguration {

    public static final String ENABLED_KEY = "geopulse.google-places.enabled";
    public static final String MONTHLY_QUOTA_KEY = "geopulse.google-places.monthly-quota";
    public static final String API_KEY_ENV_VAR = "GEOPULSE_GOOGLE_PLACES_API_KEY";

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_MONTHLY_QUOTA = 10_000;

    private final SystemSettingsService settingsService;
    private final Config config;

    @Inject
    public GooglePlacesConfiguration(SystemSettingsService settingsService, Config config) {
        this.settingsService = settingsService;
        this.config = config;
    }

    /**
     * Whether Google Places name resolution is enabled at all. Defaults to false: it costs money.
     */
    public boolean isEnabled() {
        String value = resolve(ENABLED_KEY);
        return value.isEmpty() ? DEFAULT_ENABLED : Boolean.parseBoolean(value);
    }

    /**
     * Hard cap on billable Place Details requests per calendar month (UTC).
     */
    public int getMonthlyQuota() {
        String value = resolve(MONTHLY_QUOTA_KEY);
        if (value.isEmpty()) {
            return DEFAULT_MONTHLY_QUOTA;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for setting {}: {} - falling back to {}",
                    MONTHLY_QUOTA_KEY, value, DEFAULT_MONTHLY_QUOTA);
            return DEFAULT_MONTHLY_QUOTA;
        }
    }

    /**
     * Google Places API key, from the environment only. Empty string when not configured.
     */
    public String getApiKey() {
        return config.getOptionalValue(API_KEY_ENV_VAR, String.class).orElse("").trim();
    }

    /**
     * True only when the integration is switched on and actually has a key to call with.
     */
    public boolean isUsable() {
        return isEnabled() && !getApiKey().isEmpty();
    }

    /**
     * DB value if an admin has set one, otherwise the environment, otherwise empty.
     */
    private String resolve(String key) {
        String dbValue = settingsService.getString(key);
        if (dbValue != null && !dbValue.isBlank()) {
            return dbValue.trim();
        }
        return config.getOptionalValue(key, String.class).orElse("").trim();
    }
}
