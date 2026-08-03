/**
 * FORK: great-circle interpolation for long hops in a drawn path.
 *
 * Google Timeline records a flight as a single start/end pair with nothing in
 * between, and upstream draws whatever points exist as a plain LineString. Two
 * things go wrong on a map:
 *
 *   1. A straight line in lat/lng space is only a great circle near the equator.
 *      A Victoria -> Tokyo leg drawn straight cuts across whatever happens to
 *      lie between those longitudes rather than arcing over the Pacific.
 *   2. Crossing the antimeridian, longitude jumps from +179 to -179, and the
 *      renderer draws the long way round - all the way back across Eurasia.
 *
 * Densifying long hops into an arc with *unwrapped* longitude fixes both.
 *
 * Trigger is distance, not movement type, on purpose: the same problem affects
 * any trip whose points are far apart, including flights the classifier labelled
 * UNKNOWN or CAR (on the imported dataset there were 1791 UNKNOWN trips, the
 * longest 189,543 km). Distance catches those too.
 */

const EARTH_RADIUS_KM = 6371

/** Below this, a straight segment and a great circle are visually identical. */
export const DEFAULT_HOP_THRESHOLD_KM = 200

const DEFAULT_ARC_POINTS = 64

const toRad = (deg) => (deg * Math.PI) / 180
const toDeg = (rad) => (rad * 180) / Math.PI

/** Great-circle distance in km between two [lng, lat] pairs. */
export const haversineKm = ([lng1, lat1], [lng2, lat2]) => {
  const phi1 = toRad(lat1)
  const phi2 = toRad(lat2)
  const dPhi = toRad(lat2 - lat1)
  const dLam = toRad(lng2 - lng1)
  const a =
    Math.sin(dPhi / 2) ** 2 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLam / 2) ** 2
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)))
}

/**
 * Points evenly spaced along the great circle between two [lng, lat] pairs,
 * by spherical linear interpolation.
 *
 * Longitude is unwrapped - deliberately allowed outside +-180 - so a path over
 * the antimeridian stays one continuous line. MapLibre renders that correctly;
 * clamping into range is what produces the sweep backwards across the map.
 *
 * Returns [lng, lat] (GeoJSON order), endpoints included.
 */
export const greatCirclePoints = (start, end, numPoints = DEFAULT_ARC_POINTS) => {
  const [lng1, lat1] = start
  const [lng2, lat2] = end

  const phi1 = toRad(lat1)
  const lam1 = toRad(lng1)
  const phi2 = toRad(lat2)
  const lam2 = toRad(lng2)

  const angular =
    2 *
    Math.asin(
      Math.min(
        1,
        Math.sqrt(
          Math.sin((phi2 - phi1) / 2) ** 2 +
            Math.cos(phi1) * Math.cos(phi2) * Math.sin((lam2 - lam1) / 2) ** 2
        )
      )
    )

  // Coincident points: sin(angular) below would be a divide-by-zero.
  if (angular < 1e-9) {
    return [start, end]
  }

  const points = []
  let prevLng = null

  for (let i = 0; i < numPoints; i += 1) {
    const f = i / (numPoints - 1)
    const a = Math.sin((1 - f) * angular) / Math.sin(angular)
    const b = Math.sin(f * angular) / Math.sin(angular)

    const x = a * Math.cos(phi1) * Math.cos(lam1) + b * Math.cos(phi2) * Math.cos(lam2)
    const y = a * Math.cos(phi1) * Math.sin(lam1) + b * Math.cos(phi2) * Math.sin(lam2)
    const z = a * Math.sin(phi1) + b * Math.sin(phi2)

    const lat = toDeg(Math.atan2(z, Math.sqrt(x * x + y * y)))
    let lng = toDeg(Math.atan2(y, x))

    if (prevLng !== null) {
      while (lng - prevLng > 180) lng -= 360
      while (lng - prevLng < -180) lng += 360
    }

    points.push([lng, lat])
    prevLng = lng
  }

  return points
}

/**
 * Replace every hop longer than `thresholdKm` with a great-circle arc, leaving
 * short segments untouched so ordinary driving and walking tracks are byte-for-
 * byte what they were.
 *
 * Input and output are both [lng, lat] arrays.
 */
export const densifyLongHops = (coordinates, thresholdKm = DEFAULT_HOP_THRESHOLD_KM) => {
  if (!Array.isArray(coordinates) || coordinates.length < 2) {
    return coordinates
  }

  const out = [coordinates[0]]

  for (let i = 1; i < coordinates.length; i += 1) {
    const from = coordinates[i - 1]
    const to = coordinates[i]

    if (haversineKm(from, to) > thresholdKm) {
      // Drop the arc's first point: it duplicates what is already in `out`.
      const arc = greatCirclePoints(from, to)
      for (let j = 1; j < arc.length; j += 1) {
        out.push(arc[j])
      }
    } else {
      out.push(to)
    }
  }

  // Unwrap the whole sequence, not just within each arc.
  //
  // greatCirclePoints returns longitude unwrapped, so an arc ending in Tokyo
  // comes back as about -220 rather than +140. Any ordinary point appended
  // after that arc is still in +-180, which reintroduces exactly the 360 degree
  // jump the arc was drawn to avoid - the line shoots back across the map on
  // the segment *after* the flight. Re-basing every point onto the previous one
  // keeps one continuous line across the join.
  for (let i = 1; i < out.length; i += 1) {
    const [lng, lat] = out[i]
    let adjusted = lng
    const prevLng = out[i - 1][0]
    while (adjusted - prevLng > 180) adjusted -= 360
    while (adjusted - prevLng < -180) adjusted += 360
    if (adjusted !== lng) {
      out[i] = [adjusted, lat]
    }
  }

  return out
}
