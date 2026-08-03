package org.github.tess1o.geopulse.geocoding.googleplaces.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.geocoding.googleplaces.config.GooglePlacesConfiguration;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Hard monthly cap on billable Google Places requests.
 * <p>
 * Every reservation runs in its own transaction ({@code REQUIRES_NEW}). That matters in both
 * directions: the count must survive a rollback of the surrounding timeline generation (a request
 * that was sent has been billed whether or not the caller's work commits), and it must be visible
 * to concurrent reservations immediately rather than at the end of a long-running batch.
 * <p>
 * The reservation itself is a single statement, ported from the retired app's {@code geocode_quota}
 * upsert. The {@code WHERE} clause on the {@code DO UPDATE} is what makes it safe: the row is
 * locked by the conflicting insert, the predicate is evaluated against the locked row, and a loser
 * gets zero rows back rather than an over-count. A read-then-increment pair would let two callers
 * both see {@code count == limit - 1} and both spend.
 * <p>
 * The Python original detected success with {@code RETURNING google_requests}. Here the affected
 * row count from {@code executeUpdate()} carries exactly the same signal - 1 when the row was
 * inserted or updated, 0 when the {@code WHERE} predicate refused the update - and avoids relying
 * on Hibernate treating a DML-with-RETURNING statement as a result-set query.
 */
@ApplicationScoped
@Slf4j
public class GooglePlacesQuotaService {

    private static final String RESERVE_SQL = """
            INSERT INTO google_places_quota (month, request_count)
            VALUES (:month, 1)
            ON CONFLICT (month) DO UPDATE
                SET request_count = google_places_quota.request_count + 1
                WHERE google_places_quota.request_count < :quota
            """;

    private static final String USED_SQL = """
            SELECT request_count FROM google_places_quota WHERE month = :month
            """;

    private final EntityManager entityManager;
    private final GooglePlacesConfiguration configuration;

    @Inject
    public GooglePlacesQuotaService(EntityManager entityManager, GooglePlacesConfiguration configuration) {
        this.entityManager = entityManager;
        this.configuration = configuration;
    }

    /**
     * Atomically claim one of this month's request slots.
     *
     * @return true if a slot was claimed and the caller may make exactly one billable request;
     * false once the configured monthly quota is exhausted.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean reserveRequest() {
        int quota = configuration.getMonthlyQuota();
        if (quota <= 0) {
            log.debug("Google Places monthly quota is {} - refusing all requests", quota);
            return false;
        }

        // A brand-new month inserts with request_count = 1, so the insert itself must be refused
        // when the quota is zero. Handled above; every other path goes through the conditional
        // update.
        int affected = entityManager.createNativeQuery(RESERVE_SQL)
                .setParameter("month", currentMonth())
                .setParameter("quota", quota)
                .executeUpdate();

        boolean reserved = affected > 0;
        if (!reserved) {
            log.debug("Google Places monthly quota of {} exhausted for {}", quota, currentMonth());
        }
        return reserved;
    }

    /**
     * Requests already spent this month. Diagnostics only - never use this to decide whether to
     * make a request, that is what {@link #reserveRequest()} is for.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int requestsUsedThisMonth() {
        List<?> rows = entityManager.createNativeQuery(USED_SQL)
                .setParameter("month", currentMonth())
                .getResultList();
        if (rows.isEmpty()) {
            return 0;
        }
        Object value = rows.getFirst();
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * Current calendar month in UTC, as the {@code char(7)} 'YYYY-MM' key the table uses.
     */
    static String currentMonth() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }
}
