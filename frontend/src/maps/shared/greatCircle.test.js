import { describe, it, expect } from 'vitest'
import {
  densifyLongHops,
  greatCirclePoints,
  haversineKm,
  DEFAULT_HOP_THRESHOLD_KM
} from './greatCircle'

// Roughly: home, and the two ends of a long-haul leg that crosses the Pacific.
const VICTORIA = [-123.36, 48.42]
const TOKYO = [139.69, 35.69]
const SEATTLE = [-122.33, 47.61]

describe('haversineKm', () => {
  it('measures a known long-haul distance', () => {
    // Victoria -> Tokyo is ~7,600 km.
    expect(haversineKm(VICTORIA, TOKYO)).toBeGreaterThan(7000)
    expect(haversineKm(VICTORIA, TOKYO)).toBeLessThan(8200)
  })

  it('measures a short hop', () => {
    // Victoria -> Seattle is ~120 km, i.e. under the densification threshold.
    expect(haversineKm(VICTORIA, SEATTLE)).toBeLessThan(DEFAULT_HOP_THRESHOLD_KM)
  })
})

describe('greatCirclePoints', () => {
  it('starts and ends on the given endpoints', () => {
    const arc = greatCirclePoints(VICTORIA, TOKYO)
    expect(arc[0][0]).toBeCloseTo(VICTORIA[0], 6)
    expect(arc[0][1]).toBeCloseTo(VICTORIA[1], 6)
    // Longitude may be unwrapped past -180, so compare it modulo a full turn.
    const [lastLng, lastLat] = arc[arc.length - 1]
    expect(((lastLng + 540) % 360) - 180).toBeCloseTo(TOKYO[0], 6)
    expect(lastLat).toBeCloseTo(TOKYO[1], 6)
  })

  it('arcs north of the straight lat/lng line on a Pacific crossing', () => {
    // The whole point: a great circle between two mid-latitude northern points
    // bows toward the pole. A straight line would stay between the endpoint
    // latitudes.
    const arc = greatCirclePoints(VICTORIA, TOKYO)
    const maxLat = Math.max(...arc.map(([, lat]) => lat))
    expect(maxLat).toBeGreaterThan(Math.max(VICTORIA[1], TOKYO[1]))
  })

  it('never jumps a full hemisphere between consecutive points', () => {
    // Unwrapped longitude means no +179 -> -179 discontinuity, which is what
    // makes a renderer sweep backwards across the entire map.
    const arc = greatCirclePoints(VICTORIA, TOKYO)
    for (let i = 1; i < arc.length; i += 1) {
      expect(Math.abs(arc[i][0] - arc[i - 1][0])).toBeLessThan(180)
    }
  })

  it('handles coincident endpoints without dividing by zero', () => {
    const arc = greatCirclePoints(VICTORIA, VICTORIA)
    expect(arc).toHaveLength(2)
    expect(arc.every(([lng, lat]) => Number.isFinite(lng) && Number.isFinite(lat))).toBe(true)
  })
})

describe('densifyLongHops', () => {
  it('leaves a short ground track completely untouched', () => {
    const track = [VICTORIA, [-123.0, 48.3], SEATTLE]
    expect(densifyLongHops(track)).toEqual(track)
  })

  it('expands a flight recorded as a bare start/end pair', () => {
    const densified = densifyLongHops([VICTORIA, TOKYO])
    expect(densified.length).toBeGreaterThan(10)
    expect(densified[0]).toEqual(VICTORIA)
  })

  it('densifies only the long hop in a mixed track', () => {
    // drive, then fly, then drive
    const mixed = [VICTORIA, SEATTLE, TOKYO, [139.7, 35.7]]
    const densified = densifyLongHops(mixed)
    expect(densified.length).toBeGreaterThan(mixed.length + 10)
    // Every original point still appears. Longitudes past the antimeridian are
    // unwrapped (Tokyo comes back as about -220, not +140), so compare modulo a
    // full turn rather than by exact value.
    const sameLng = (a, b) => Math.abs((((a - b) % 360) + 540) % 360 - 180) < 1e-6
    for (const point of mixed) {
      expect(
        densified.some(([lng, lat]) => sameLng(lng, point[0]) && Math.abs(lat - point[1]) < 1e-6)
      ).toBe(true)
    }
  })

  it('keeps the line continuous across the point *after* a flight', () => {
    // Regression: the arc ends on an unwrapped longitude, and the next ordinary
    // point used to be appended still in +-180 - a 360 degree jump on the
    // segment following the flight, which renders as a sweep back across the
    // whole map.
    const densified = densifyLongHops([VICTORIA, TOKYO, [139.7, 35.7]])
    for (let i = 1; i < densified.length; i += 1) {
      expect(Math.abs(densified[i][0] - densified[i - 1][0])).toBeLessThan(180)
    }
  })

  it('returns input unchanged when there is nothing to join', () => {
    expect(densifyLongHops([])).toEqual([])
    expect(densifyLongHops([VICTORIA])).toEqual([VICTORIA])
    expect(densifyLongHops(null)).toBeNull()
  })

  it('produces no hemisphere-sized jumps across a densified flight', () => {
    const densified = densifyLongHops([VICTORIA, TOKYO])
    for (let i = 1; i < densified.length; i += 1) {
      expect(Math.abs(densified[i][0] - densified[i - 1][0])).toBeLessThan(180)
    }
  })
})
