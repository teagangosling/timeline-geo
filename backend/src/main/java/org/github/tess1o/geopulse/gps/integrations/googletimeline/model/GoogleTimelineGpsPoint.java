package org.github.tess1o.geopulse.gps.integrations.googletimeline.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Extracted GPS point from Google Timeline data
 */
@Data
@Builder
public class GoogleTimelineGpsPoint {

    private Instant timestamp;
    private double latitude;
    private double longitude;
    private String recordType; // activity_start, activity_end, visit, timeline_point
    private String activityType;
    private double confidence;
    private Double velocityMs; // velocity in m/s, null if not available
    private Double accuracy; // accuracy in meters, null if not available
    private Double altitude; // altitude in meters, null if not available
    private int recordIndex; // index in original data
    // FORK: Google's placeID for the visit this point belongs to, used to name stays via the
    // Places API. Null for every point that is not part of a visit.
    private String placeId;
    // FORK: true for the synthetic points interpolated across a visit's duration, false for real
    // fixes. Lets the merge/naming code tell "Google says you were here" from "a GPS fix said so".
    private boolean syntheticVisitPoint;
}